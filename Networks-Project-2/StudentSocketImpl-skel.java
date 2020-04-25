import java.net.*;
import java.io.*;
import java.util.Timer;

class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;

  private Demultiplexer D;
  private Timer tcpTimer;
  private int ackNumber;
  private int seqNumber;
  private int seqNumberPlusOne;
  private int windowSize = 5; // arbitrary
  
  // Timeout length for unACKed packets
  // milliseconds
  private static final int timerDelay = 2500; 
  
  private enum State {
	  CLOSED,
	  LISTEN,
	  SYN_SENT,
	  SYN_RCVD,
	  ESTABLISHED,
	  FIN_WAIT_1,
	  FIN_WAIT_2,
	  CLOSE_WAIT,
	  LAST_ACK,
	  CLOSING,
	  TIME_WAIT
  }
  
  private State currentState;

  StudentSocketImpl(Demultiplexer D) {  // default constructor
    this.D = D;
    currentState = State.CLOSED;
  }

  /**
   * Connects this socket to the specified port number on the specified host.
   *
   * @param      address   the IP address of the remote host.
   * @param      port      the port number.
   * @exception  IOException  if an I/O error occurs when attempting a
   *               connection.
   */
  public synchronized void connect(InetAddress address, int port) throws IOException {	
	localport = D.getNextAvailablePort();
	this.address = address;
	this.port = port;
    D.registerConnection(address, localport, port, this);    
    
    stateChange(State.SYN_SENT);
    
    ackNumber = 70;
    seqNumberPlusOne = 30;
    
    sendPacketWithTimer(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber, 
			false, true, false, windowSize, null));
    
    
    while (!(currentState == State.ESTABLISHED)) {
    	try {
			wait();
		} catch (Exception e) {
		}
    }
  }
  
  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p){
	  this.notifyAll();
	  
	  switch (currentState) {
	  	case LISTEN:
	  		if (p.synFlag) {
				try {
					D.unregisterListeningSocket(localport, this);
					D.registerConnection(p.sourceAddr, localport, p.sourcePort, this);
				} catch (IOException e) {
					System.out.println(e);
					e.printStackTrace();
				}
				
				updateSeqNumber(p.seqNum);
				ackNumber = p.ackNum;
				this.address = p.sourceAddr;
				this.localport = p.destPort;
				this.port = p.sourcePort;
				
				stateChange(State.SYN_RCVD);
				
				sendPacketWithTimer(new TCPPacket(localport, port, seqNumber, ackNumber, 
						true, true, false, windowSize, null));
	  		}
	  		break;
	  		
	  	case SYN_SENT:
	  		if (p.synFlag && p.ackFlag) {
	  			stateChange(State.ESTABLISHED);
	  			updateSeqNumber(p.seqNum);
	  			this.address = p.sourceAddr;
	  			this.port = p.sourcePort;
	  			
	  			sendPacket(new TCPPacket(localport, port, seqNumber, ackNumber, 
	  					true, false, false, windowSize, null));
	  		}
	  		break;
	  	
	  	case SYN_RCVD:
	  		if (p.ackFlag) {
	  			stateChange(State.ESTABLISHED);
	  			this.port = p.sourcePort;
	  		} else if (p.synFlag) {
	  			// Prematurely resend SYNACK packet
	  			handleTimer(new TCPPacket(localport, port, seqNumber, ackNumber, 
						true, true, false, windowSize, null));
	  		}
	  		break;
	  	
	  	case ESTABLISHED:
	  		if (p.finFlag) {
	  			updateSeqNumber(p.seqNum);
	  			this.address = p.sourceAddr;
	  			this.localport = p.sourcePort;
	  			
	  			stateChange(State.CLOSE_WAIT);
	  			
	  			sendPacket(new TCPPacket(localport, port, seqNumber, ackNumber, 
	  					true, false, false, windowSize, null));
	  		} else if (p.ackFlag && p.synFlag) {
	  			// Prematurely resend ACK packet
	  			handleTimer(new TCPPacket(localport, port, seqNumber, ackNumber,
	  					true, true, false, windowSize, null));
	  		}
	  		break;
	  }
	  System.out.println(p.toString());
  }
  
  /** 
   * Waits for an incoming connection to arrive to connect this socket to
   * Ultimately this is called by the application calling 
   * ServerSocket.accept(), but this method belongs to the Socket object 
   * that will be returned, not the listening ServerSocket.
   * Note that localport is already set prior to this being called.
   */
  public synchronized void acceptConnection() throws IOException {
	  D.registerListeningSocket(localport, this);
	  
	  stateChange(State.LISTEN);
  }

  
  /**
   * Returns an input stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     a stream for reading from this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               input stream.
   */
  public InputStream getInputStream() throws IOException {
    // project 4 return appIS;
    return null;
    
  }

  /**
   * Returns an output stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     an output stream for writing to this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               output stream.
   */
  public OutputStream getOutputStream() throws IOException {
    // project 4 return appOS;
    return null;
  }


  /**
   * Closes this socket. 
   *
   * @exception  IOException  if an I/O error occurs when closing this socket.
   */
  public synchronized void close() throws IOException {
  }

  /** 
   * create TCPTimerTask instance, handling tcpTimer creation
   * @param delay time in milliseconds before call
   * @param ref generic reference to be returned to handleTimer
   */
  private TCPTimerTask createTimerTask(long delay, Object ref){
    if(tcpTimer == null)
    	tcpTimer = new Timer(false);
    return new TCPTimerTask(tcpTimer, delay, this, ref);
  }
  
  private void stateChange(State state) {
	  System.out.println("!!! " + currentState + "->" + state);
	  currentState = state;
	  if (!(tcpTimer == null)) {
		  tcpTimer.cancel();
		  tcpTimer = null;
	  }
  }
  
  private void updateSeqNumber(int n) {
	  seqNumber = n;
	  seqNumberPlusOne = n+1;
  }
  
  private void sendPacket(TCPPacket packet) {
	  // If we force a resend before timer expires, need to reset timer.
	  if (!(tcpTimer == null)) {
		  tcpTimer.cancel();
		  tcpTimer = null;
	  }
	  
	  System.out.println("Sending packet with seqnumber " + packet.seqNum + ".");
	  
	  TCPWrapper.send(packet, this.address);
  }
  private void sendPacketWithTimer(TCPPacket packet) {
	  // If we force a resend before timer expires, need to reset timer.
	  if (!(tcpTimer == null)) {
		  tcpTimer.cancel();
		  tcpTimer = null;
	  }
	  
	  System.out.println("Sending packet with seqnumber " + packet.seqNum + " and starting timer...");
	  TCPTimerTask timerTask = createTimerTask(timerDelay, packet);
	  
	  TCPWrapper.send(packet, this.address);
  }
  
  /**
   * handle timer expiration (called by TCPTimerTask)
   * @param ref Generic reference that can be used by the timer to return 
   * information.
   */
  public synchronized void handleTimer(Object ref){
    // this must run only once the last timer (30 second timer) has expired
    tcpTimer.cancel();
    tcpTimer = null;
    
    if (ref != null) {
    	TCPPacket p = (TCPPacket) ref;
    	System.out.println("Resending packet with seqnumber " + p.seqNum + "...");
    	sendPacket(p);
    }
    else {
    	System.out.println("why are you here i don't know");
    	if (currentState == State.TIME_WAIT) {
    		System.out.println("30 second timer complete. Returning to CLOSED state.");
    		stateChange(State.CLOSED);
    	}
    }
  }
}

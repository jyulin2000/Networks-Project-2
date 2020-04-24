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
  private int windowSize;
  
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
    D.registerConnection(address, localport, port, this);
    this.address = address; // MIGHT WANT TO REMOVE THIS LINE BECAUSE OF STUFF
    windowSize = 6; // Arbitrary idk what to do with this yet
    
    sendSYNPacket();
    
    stateChange(State.SYN_SENT);
  }
  
  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p){
	  this.notifyAll();
	  
	  switch (currentState) {
	  	case LISTEN:
	  		System.out.println("Received packet in state LISTEN.");
	  		if (p.synFlag) {
				try {
					D.unregisterListeningSocket(localport, this);
				} catch (IOException e) {
					System.out.println(e);
					e.printStackTrace();
				}
				try {
					D.registerConnection(p.sourceAddr, localport, p.sourcePort, this);
				} catch (IOException e) {
					System.out.println(e);
					e.printStackTrace();
				}
				
				TCPWrapper.send(new TCPPacket(localport, port, 0, 0, 
						true, true, false, windowSize, null), address);
				
				stateChange(State.SYN_RCVD);
	  		}
	  	
	  	case SYN_SENT:
	  		if (p.synFlag && p.ackFlag) {
	  			stateChange(State.ESTABLISHED);
	  		}
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
  }
  
  private void sendSYNPacket() {
	  TCPWrapper.send(new TCPPacket(localport, port, 0, 0, 
				false, true, false, windowSize, null), address);
  }
  
  private void sendSYNACKPacket() {
	  TCPWrapper.send(new TCPPacket(localport, port, 0, 0, 
				true, true, false, windowSize, null), address);
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
  }
}

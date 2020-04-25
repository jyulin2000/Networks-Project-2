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
  private static final int timerDelay = 1000;
  
  // 30 second closing wait time
  private static final int closeDelay = 30 * 1000;
  
  public enum State {
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
  public State getState() {
	  return currentState;
  }

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
				
				sendPacketWithTimer(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber, 
						true, true, false, windowSize, null));
	  		}
	  		break;
	  		
	  	case SYN_SENT:
	  		if (p.synFlag && p.ackFlag) {
	  			stateChange(State.ESTABLISHED);
	  			updateSeqNumber(p.seqNum);
	  			this.address = p.sourceAddr;
	  			this.port = p.sourcePort;
	  			
	  			sendPacket(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber, 
	  					true, false, false, windowSize, null));
	  		}
	  		break;
	  	
	  	case SYN_RCVD:
	  		if (p.ackFlag) {
	  			stateChange(State.ESTABLISHED);
	  			this.port = p.sourcePort;
	  		} else if (p.synFlag) {
	  			// Prematurely resend SYNACK packet
	  			handleTimer(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber, 
						true, true, false, windowSize, null));
	  		}
	  		break;
	  	
	  	case ESTABLISHED:
	  		if (p.finFlag) {
	  			updateSeqNumber(p.seqNum);
	  			/*
	  			this.address = p.sourceAddr;
	  			this.localport = p.sourcePort;
	  			*/
	  			
	  			stateChange(State.CLOSE_WAIT);
	  			
	  			sendPacket(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber, 
	  					true, false, false, windowSize, null));
	  		} else if (p.ackFlag && p.synFlag) {
	  			// Prematurely resend ACK packet
	  			sendPacket(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber,
	  					true, false, false, windowSize, null));
	  		}
	  		break;
	  	
	  	case FIN_WAIT_1:
	  		if (p.finFlag) {
	  			updateSeqNumber(p.seqNum);
	  			/*
	  			address = p.sourceAddr;
	  			ackNumber = p.ackNum;
	  			port = p.sourcePort;
	  			*/
	  			
	  			stateChange(State.CLOSING);
	  			sendPacketWithTimer(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber, 
	  					true, false, false, windowSize, null));
	  		} else if (p.ackFlag) {
	  			if (!p.synFlag) {
	  				stateChange(State.FIN_WAIT_2);
	  			} else {
	  				sendPacket(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber,
		  					true, false, false, windowSize, null));
	  			}
	  		}
	  		break;
	  	
	  	case FIN_WAIT_2:
	  		if (p.finFlag) {
	  			updateSeqNumber(p.seqNum);
	  			/*
	  			address = p.sourceAddr;
	  			ackNumber = p.ackNum;
	  			port = p.sourcePort;
	  			*/
	  			sendPacket(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber,
	  					true, false, false, windowSize, null));
	  			
	  			// stateChange function will start the timer
	  			stateChange(State.TIME_WAIT);
	  		} else if (p.ackFlag) {
	  			stateChange(State.TIME_WAIT);
	  			resendACKPacket();
	  		}
	  		break;
	  	
	  	case CLOSE_WAIT:
	  		if (p.finFlag) {
	  			resendACKPacket();
	  		} else if (p.ackFlag) {
	  			resendACKPacket();
	  		}
	  		break;
	  		
	  	case LAST_ACK:
	  		if (p.ackFlag) {
	  			// stateChange function will start the timer
	  			stateChange(State.TIME_WAIT);
	  		}
	  		if (p.finFlag) {
	  			resendACKPacket();
	  		}
	  		break;
	  	
	  	case TIME_WAIT:
	  		if (p.finFlag) {
	  			resendACKPacket();
	  		} else if (p.ackFlag) {
	  			resendACKPacket();
	  		}
	  		break;
	  	
	  	case CLOSING:
	  		if (p.ackFlag) {
	  			// stateChange function will start the timer
	  			stateChange(State.TIME_WAIT);
	  		} else if (p.finFlag) {
	  			sendPacketWithTimer(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber, 
	  					true, false, false, windowSize, null));
	  		}
	  		break;
	  	
	  	default:
	  		break;
	  }
	  
	  this.notifyAll();
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
	  while (!(currentState == State.ESTABLISHED)) {
	    	try {
				wait();
			} catch (Exception e) {
			}
	    }
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
	  if (currentState == State.CLOSE_WAIT) {
		  stateChange(State.LAST_ACK);
	  } else if (currentState == State.ESTABLISHED) {
		  stateChange(State.FIN_WAIT_1);
	  } else {
		  return;
	  }
	  
	  sendPacketWithTimer(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber,
			  false, false, true, windowSize, null));
	  
	  try {
		  CloseThread closer = new CloseThread(this);
		  closer.run();
	  } catch (Exception e) {
	  }
	  
	  return;
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
	  
	  if (state == State.TIME_WAIT) {
		  createTimerTask(closeDelay, null);
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
  
  private void resendACKPacket() {
	  sendPacket(new TCPPacket(localport, port, seqNumberPlusOne, ackNumber,
				true, false, false, windowSize, null));
  }
  
  private void sendPacketWithTimer(TCPPacket packet) {
	  // If we force a resend before timer expires, need to reset timer.
	  if (!(tcpTimer == null)) {
		  tcpTimer.cancel();
		  tcpTimer = null;
	  }
	  
	  if (packet == null) return;
	  
	  try {
		  TCPWrapper.send(packet, this.address);
		  createTimerTask(timerDelay, packet);
	  } catch (Exception e) {
		  return;
	  }
	  
	  System.out.println("Sending packet with seqnumber " + packet.seqNum + " and starting timer...");
  }
  
  private class CloseThread implements Runnable {
	  public StudentSocketImpl closingThread;
	  
	  public CloseThread(StudentSocketImpl sock) throws InterruptedException {
		  closingThread = sock;
	  }
	  
	  public void run() {
		  while (closingThread.getState() != State.CLOSED) {
			  try {
				  closingThread.wait();
			  } catch (Exception e) {
			  }
		  }
	  }
  }
  
  /**
   * handle timer expiration (called by TCPTimerTask)
   * @param ref Generic reference that can be used by the timer to return 
   * information.
   */
  public synchronized void handleTimer(Object ref){
    // this must run only once the last timer (30 second timer) has expired
	  if (!(tcpTimer == null)) {
		  tcpTimer.cancel();
		  tcpTimer = null;
	  }
    
    if (ref != null) {
    	TCPPacket p = (TCPPacket) ref;
    	System.out.println("Resending packet with seqnumber " + p.seqNum + "...");
    	sendPacketWithTimer(p);
    }
    else {
    	if (currentState == State.TIME_WAIT) {
    		System.out.println("30 second timer complete. Returning to CLOSED state.");
    		try {
	    		stateChange(State.CLOSED);
	    		notifyAll();
    		} catch (Exception e) {
    		}
    		this.notifyAll();
    		try {
    			D.unregisterConnection(address, localport, port, this);
    		} catch (Exception e) {
    		}
    	}
    }
  }
}

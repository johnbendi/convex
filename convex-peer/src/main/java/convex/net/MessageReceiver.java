package convex.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
import convex.peer.Config;

/**
 * Class responsible for buffered accumulation of data received over a connection.
 *
 * ByteBuffers received must be passed in via @receiveFromChannel
 *
 * Passes any successfully received objects to a specified Consumer, using the same thread on which the
 * MessageReceiver was called.
 *
 * <blockquote>
 *   <p>"There are only two hard problems in distributed systems: 2. Exactly-once
 *   delivery 1. Guaranteed order of messages 2. Exactly-once delivery"
 *   </p>
 *   <footer>- attributed to Mathias Verraes</footer>
 * </blockquote>
 *
 *
 */
public class MessageReceiver {
	// Receive buffer must be big enough at least for one max sized message plus message header
	public static final int RECEIVE_BUFFER_SIZE = Config.RECEIVE_BUFFER_SIZE;

	/**
	 * Buffer for receiving partial messages. Maintained ready for writing.
	 * 
	 * Maybe use a direct buffer since we are copying from the socket channel? But probably doesn't make any difference.
	 */
	private ByteBuffer buffer = ByteBuffer.allocate(RECEIVE_BUFFER_SIZE);

	private final Consumer<Message> action;
	private Consumer<Message> hook=null;
	private final Connection connection;

	private long receivedMessageCount = 0;

	private static final Logger log = LoggerFactory.getLogger(MessageReceiver.class.getName());

	public MessageReceiver(Consumer<Message> receiveAction, Connection pc) {
		this.action = receiveAction;
		this.connection = pc;
	}

	public Consumer<Message> getReceiceAction() {
		return action;
	}

	/**
	 * Get the number of messages received in total by this Receiver
	 * @return Count of messages received
	 */
	public long getReceivedCount() {
		return receivedMessageCount;
	}

	/**
	 * Handles receipt of bytes from a channel. Should be called with a
	 * ReadableByteChannel containing bytes received.
	 *
	 * May be called multiple times during receipt of a single message, i.e. can
	 * handle partial message receipt.
	 *
	 * Will consume enough bytes from channel to handle exactly one message. Bytes
	 * will be left unconsumed on the channel if more are available.
	 *
	 * This hopefully
	 * creates sufficient backpressure on clients sending a lot of messages.
	 *
	 * @param chan Byte channel
	 * @throws IOException If IO error occurs
	 * @return The number of bytes read from the channel, or -1 if EOS
	 * @throws BadFormatException If a bad encoding is received
	 */
	public synchronized int receiveFromChannel(ReadableByteChannel chan) throws IOException, BadFormatException {
		int numRead=0;

		numRead = chan.read(buffer);

		if (numRead <= 0) {
			// no bytes received / at end of stream
			return numRead;
		}

		while (buffer.position()>0) {
			// peek message length at start of buffer. May throw BFE.
			int len = Format.peekMessageLength(buffer);
			if (len<0) return numRead; // Not enough bytes for a message length yet
			
			int lengthLength = Format.getVLCLength(len);
			int totalFrameSize=lengthLength + len;
			
			if (totalFrameSize>buffer.capacity()) {
				int newSize=Math.max(totalFrameSize, buffer.position());
				ByteBuffer newBuffer=ByteBuffer.allocate(newSize);
				buffer.flip();
				newBuffer.put(buffer);
				buffer=newBuffer;
			}
			
			// Exit if we hven't got the full message yet
			if (buffer.position()<totalFrameSize) return numRead;
	
			byte mType=buffer.get(lengthLength);
			MessageType type=MessageType.decode(mType);
			
			byte[] bs=new byte[len-1]; // message data length after type byte
			buffer.get(lengthLength+1, bs, 0, len-1);
			
			Blob messageData=Blob.wrap(bs);
			receiveMessage(type, messageData);
	
			int receivedLimit=buffer.position();
			buffer.position(totalFrameSize);
			buffer.limit(receivedLimit);
			buffer.compact();
		}
		return numRead;
	}

	/**
	 * Reads exactly one message from the ByteBuffer, checking that the position is
	 * advanced as expected. Buffer must contain sufficient bytes for given message length.
	 *
	 * Expects a message code at the buffer's current position.
	 *
	 * Calls the receive action with the message if successfully received. Should be called with
	 * the correct store for this Connection.
	 *
	 * SECURITY: Gets called on NIO thread for Server / Client connections
	 *
	 * @throws BadFormatException if the message is incorrectly formatted`
	 */
	private void receiveMessage(MessageType type, Blob encoding) throws BadFormatException {
		try {
			Message message = Message.create(connection, type, encoding);
			
			// call the receiver hook, if registered
			maybeCallHook(message);
			
			// Otherwise, send to the message receive action
			receivedMessageCount++;
			if (action != null) {
				log.trace("Message received: {}", message.getType());
				action.accept(message);
			} else {
				log.warn("Ignored message because no receive action set: " + message);
			}
		} catch (Throwable e) {
			// TODO: handle Throwable vs Exception differently? Close connection?
			log.warn("Exception in receive action from: " + connection.getRemoteAddress(),e);
		}
	}

	private void maybeCallHook(Message message) {
		Consumer<Message> hook=this.hook;
		if (hook!=null) {
			hook.accept(message);
		}
	}

	/**
	 * Sets an optional additional message receiver hook (for debugging / observability purposes)
	 * @param hook Hook to call when a message is received
	 */
	public void setHook(Consumer<Message> hook) {
		this.hook = hook;
	}

}

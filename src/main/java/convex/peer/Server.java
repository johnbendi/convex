package convex.peer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.api.Shutdown;
import convex.core.Belief;
import convex.core.Block;
import convex.core.BlockResult;
import convex.core.ErrorCodes;
import convex.core.Peer;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.Context;
import convex.core.lang.impl.AExceptional;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;
import convex.net.Connection;
import convex.net.Message;
import convex.net.MessageType;
import convex.net.NIOServer;

/**
 * A self contained server that can be launched with a config.
 * 
 * Server creates the following threads:
 * - A ReceiverThread that precesses message from the Server's receive Queue
 * - An UpdateThreat that handles Belief updates and transaction processing
 * 
 * "Programming is a science dressed up as art, because most of us don't
 * understand the physics of software and it's rarely, if ever, taught. The
 * physics of software is not algorithms, data structures, languages, and
 * abstractions. These are just tools we make, use, and throw away. The real
 * physics of software is the physics of people. Specifically, it's about our
 * limitations when it comes to complexity and our desire to work together to
 * solve large problems in pieces. This is the science of programming: make
 * building blocks that people can understand and use easily, and people will
 * work together to solve the very largest problems." ― Pieter Hintjens
 *
 */
public class Server implements Closeable {
	public static final int DEFAULT_PORT = 18888;

	private static final int RECEIVE_QUEUE_SIZE = 10000;

	// MAximum Pause for each iteration of Server update loop.
	private static final long SERVER_UPDATE_PAUSE = 1L;

	private static final Logger log = Logger.getLogger(Server.class.getName());
	private static final Level LEVEL_BELIEF = Level.FINER;
	private static final Level LEVEL_SERVER = Level.FINER;
	private static final Level LEVEL_DATA = Level.FINEST;
	private static final Level LEVEL_PARTIAL = Level.WARNING;
	// private static final Level LEVEL_MESSAGE = Level.FINER;
	
	/**
	 * Queue for received messages to be processed by this Peer Server
	 */
	private BlockingQueue<Message> receiveQueue = new ArrayBlockingQueue<Message>(RECEIVE_QUEUE_SIZE);

	/**
	 * Message consumer that simply enqueues received messages. Used for outward
	 * connections. i.e. ones this Server has made.
	 */
	private Consumer<Message> peerReceiveAction = new Consumer<Message>() {
		@Override
		public void accept(Message msg) {
			receiveQueue.add(msg);
		}
	};

	/**
	 * Connection manager instance.
	 */
	protected ConnectionManager manager;

	/**
	 * Store to use for all threads associated with this server instance
	 */
	private final AStore store;
	
	private final HashMap<Keyword, Object> config;

	private boolean running = false;
	
	/**
	 * Flag to indicate if there are any new things for the server to process (Beliefs, transactions)
	 * can safely sleep a bit if nothing to do
	 */
	private boolean newThings = false;

	private NIOServer nio;
	private Thread receiverThread = null;
	private Thread updateThread = null;

	/** 
	 * The Peer instance current state for this server. Will be updated based on peer events. 
	 */
	private Peer peer;

	/**
	 * The list of transactions for the block being created Should only modify with
	 * the lock for this Server held.
	 * 
	 * Must all have been fully persisted.
	 */
	private ArrayList<SignedData<ATransaction>> newTransactions = new ArrayList<>();

	/**
	 * The set of queued partial messages pending missing data.
	 * 
	 * Delivery will be re-attempted when missing data is provided
	 */
	private HashMap<Hash, Message> partialMessages = new HashMap<Hash, Message>();

	/**
	 * The list of new beliefs received from remote peers the block being created
	 * Should only modify with the lock for this Server help.
	 */
	private HashMap<AccountKey, SignedData<Belief>> newBeliefs = new HashMap<>();

	private Server(HashMap<Keyword, Object> config) {
		AStore configStore = (AStore) config.get(Keywords.STORE);
		this.store = (configStore == null) ? Stores.getGlobalStore() : configStore;
		
		AKeyPair keyPair = (AKeyPair) config.get(Keywords.KEYPAIR);
		if (keyPair==null) throw new IllegalArgumentException("No Peer Key Pair provided in config");
		
		// Switch to use the configured store, saving the caller store
		final AStore savedStore=Stores.current();
		try {
			Stores.setCurrent(store);
			this.config = config;
			this.manager = new ConnectionManager(config);
	
	
			this.peer = establishPeer(keyPair, config);
	
			nio = NIOServer.create(this, receiveQueue);
		} finally {
			Stores.setCurrent(savedStore);
		}
	}

	private Peer establishPeer(AKeyPair keyPair, Map<Keyword, Object> config2) {
		log.info("Establishing Peer with store: "+Stores.current());

		if (Utils.bool(config.get(Keywords.RESTORE))) {
			try {
				Hash hash = store.getRootHash();
				Peer peer = Peer.restorePeer(store, hash, keyPair);
				if (peer != null) return peer;
			} catch (Throwable e) {
				log.warning("Can't restore Peer from store: " + e.getMessage());
			}
		}
		log.info("Defaulting to standard Peer startup.");
		return Peer.createStartupPeer(config);
	}

	/**
	 * Creates a Server with a given config. Reference to config is kept: don't
	 * mutate elsewhere.
	 * 
	 * @param config
	 * @return
	 */
	public static Server create(HashMap<Keyword, Object> config) {
		return new Server(config);
	}

	/**
	 * Gets the current Belief held by this PeerServer
	 * 
	 * @return Current Belief
	 */
	public Belief getBelief() {
		return peer.getBelief();
	}

	/**
	 * Gets the current Belief held by this PeerServer
	 * 
	 * @return Current Belief
	 */
	public Peer getPeer() {
		return peer;
	}

	public synchronized void launch() {
		Object p = config.get(Keywords.PORT);
		Integer port = (p == null) ? null : Utils.toInt(p);

		try {
			nio.launch(port);
			port = nio.getPort(); // get the actual port (may be auto-allocated)

			// set running status now, so that loops don't terminate
			running = true;

			receiverThread = new Thread(receiverLoop, "Receive queue worker loop serving port: " + port);
			receiverThread.setDaemon(true);
			receiverThread.start();

			updateThread = new Thread(updateLoop, "Server Belief update loop for port: " + port);
			updateThread.setDaemon(true);
			updateThread.start();

			// Close server on shutdown, must be before Etch stores
			Shutdown.addHook(Shutdown.SERVER, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});

			log.log(LEVEL_SERVER, "Peer Server started with Peer Address: " + getAddress().toChecksumHex());
		} catch (Exception e) {
			throw new Error("Failed to launch Server on port: " + port, e);
		}
	}

	/**
	 * Process a message received from a peer or client. We know at this point that the
	 * message parsed successfully, not much else.....
	 * 
	 * If the message is partial, will be queued pending delivery of missing data
	 * 
	 * @param m
	 */
	private void processMessage(Message m) {
		MessageType type = m.getType();

		try {
			switch (type) {
			case BELIEF:
				processBelief(m);
				break;
			case CHALLENGE:
				break;
			case COMMAND:
				break;
			case DATA:
				processData(m);
				break;
			case MISSING_DATA:
				processMissingData(m);
				break;
			case QUERY:
				processQuery(m);
				break;
			case RESPONSE:
				break;
			case RESULT:
				break;
			case TRANSACT:
				processTransact(m);
				break;
			case GOODBYE:
				m.getPeerConnection().close();
				break;
			case STATUS:
				processStatus(m);
				break;
			}
			
		} catch (MissingDataException e) {
			Hash missingHash = e.getMissingHash();
			log.log(LEVEL_PARTIAL, "Missing data: " + missingHash.toHexString() + " in message of type " + type);
			try {
				registerPartialMessage(missingHash, m);
				m.getPeerConnection().sendMissingData(missingHash);
				log.log(LEVEL_PARTIAL,
						() -> "Requested missing data " + missingHash.toHexString() + " for partial message");
			} catch (IOException ex) {
				log.log(Level.WARNING, () -> "Exception while requesting missing data: " + ex.getMessage());
			}
		} catch (BadFormatException | ClassCastException | NullPointerException e) {
			log.warning("Error processing client message: " + e);
		}
	}

	/**
	 * Respond to a request for missing data, on a best-efforts basis. Requests for
	 * missing data we do not hold are ignored.
	 * 
	 * @param m
	 * @throws BadFormatException
	 */
	private void processMissingData(Message m) throws BadFormatException {
		// payload for a missing data request should be a valid Hash
		Hash h = m.getPayload();
		if (h == null) throw new BadFormatException("Hash required for missing data message");

		Ref<?> r = store.refForHash(h);
		if (r != null) {
			try {
				ACell data = r.getValue();
				m.getPeerConnection().sendData(data);
				log.info(() -> "Sent missing data for hash: " + h.toHexString() + " with type "
						+ Utils.getClassName(data));
			} catch (IOException e) {
				log.info(() -> "Unable to deliver missing data for " + h.toHexString() + " due to: " + e.getMessage());
			}
		} else {
			log.warning(
					() -> "Unable to provide missing data for " + h.toHexString() + " from store: " + Stores.current());
		}
	}

	@SuppressWarnings("unchecked")
	private void processTransact(Message m) {
		// query is a vector [id , signed-object]
		AVector<ACell> v = m.getPayload();
		SignedData<ATransaction> sd = (SignedData<ATransaction>) v.get(1);

		// TODO: this should throw MissingDataException?
		ACell.createPersisted(sd);

		if (!sd.checkSignature()) {
			// terminate the connection, dishonest client?
			try {
				// TODO: throttle?
				m.getPeerConnection().sendResult(m.getID(), Strings.create("Bad Signature!"), ErrorCodes.SIGNATURE);
			} catch (IOException e) {
				// Ignore??
			}
			log.warning("Bad signature from Client! " + sd);
			return;
		}

		synchronized (newTransactions) {
			newThings=true;
			newTransactions.add(sd);
			registerInterest(sd.getHash(), m);
		}
	}

	/**
	 * Checks if received data fulfils the requirement for a partial message If so,
	 * process the message again.
	 * 
	 * @param hash
	 * @return true if the data request resulted in a re-queued message, false
	 *         otherwise
	 */
	private boolean maybeProcessPartial(Hash hash) {
		Message m;
		synchronized (partialMessages) {
			m = partialMessages.get(hash);

			if (m != null) {
				log.log(LEVEL_PARTIAL,
						() -> "Attempting to re-queue partial message due to received hash: " + hash.toHexString());
				if (receiveQueue.offer(m)) {
					partialMessages.remove(hash);
					return true;
				} else {
					log.log(Level.WARNING, () -> "Queue full for message with received hash: " + hash.toHexString());
				}
			}
		}
		return false;
	}

	/**
	 * Stores a partial message for potential later handling.
	 * 
	 * @param missingHash Hash of missing data dependency
	 * @param m           Message to re-attempt later when missing data is received.
	 */
	private void registerPartialMessage(Hash missingHash, Message m) {
		synchronized (partialMessages) {
			log.log(LEVEL_PARTIAL, () -> "Registering partial message with missing hash: " + missingHash);
			partialMessages.put(missingHash, m);
		}
	}

	/**
	 * Register of client interests in receiving message responses
	 */
	private HashMap<Hash, Message> interests = new HashMap<>();

	private void registerInterest(Hash hash, Message m) {
		interests.put(hash, m);
	}

	/**
	 * Handle general Belief update, taking belief registered in newBeliefs
	 * 
	 * @throws InterruptedException
	 */
	protected boolean maybeUpdateBelief() throws InterruptedException {
		long oldConsensusPoint = peer.getConsensusPoint();

		Belief initialBelief = peer.getBelief();

		// published new blocks if needed. Guaranteed to change belief if this happens
		boolean published = maybePublishBlock();

		// only do belief merge if needed: either after publishing a new block or with
		// incoming beliefs
		if ((!published) && newBeliefs.isEmpty()) return false;

		maybeMergeBeliefs();

		// Need to check if belief changed from initial state
		// It is possible that incoming beliefs don't change current belief.
		final Belief belief = peer.getBelief();
		if (belief == initialBelief) return false;

		// At this point we know something updated our belief, so we want to rebroadcast
		// belief to network
		Consumer<Ref<ACell>> noveltyHandler = r -> {
			ACell o = r.getValue();
			if (o == belief) return; // skip sending data for belief cell itself, will be BELIEF payload
			Message msg = Message.createData(o);
			manager.broadcast(msg);
		};

		// persist the state of the Peer, announcing the new Belief
		peer=peer.persistState(noveltyHandler);
		
		// Broadcast latest Belief to connected Peers
		SignedData<Belief> sb = peer.getSignedBelief();
		Message msg = Message.createBelief(sb);
		manager.broadcast(msg);

		// Report transaction results
		long newConsensusPoint = peer.getConsensusPoint();
		if (newConsensusPoint > oldConsensusPoint) {
			log.log(LEVEL_BELIEF, "Consenus update from " + oldConsensusPoint + " to " + newConsensusPoint);
			for (long i = oldConsensusPoint; i < newConsensusPoint; i++) {
				Block block = peer.getPeerOrder().getBlock(i);
				BlockResult br = peer.getBlockResult(i);
				reportTransactions(block, br);
			}
		}

		return true;
	}

	/**
	 * Checks for pending transactions, and if found propose them as a new Block.
	 * 
	 * @return True if a new block is published, false otherwise.
	 */
	protected boolean maybePublishBlock() {
		synchronized (newTransactions) {
			int n = newTransactions.size();
			if (n == 0) return false;
			// TODO: smaller block if too many transactions?
			long timestamp = Utils.getCurrentTimestamp();
			Block block = Block.create(timestamp, (List<SignedData<ATransaction>>) newTransactions, peer.getPeerKey());

			ACell.createPersisted(block);

			try {
				Peer newPeer = peer.proposeBlock(block);
				log.log(LEVEL_BELIEF, "New block proposed: " + block.getHash().toHexString());
				newTransactions.clear();
				peer = newPeer;
				return true;
			} catch (BadSignatureException e) {
				// TODO what to do here?
				return false;
			}
		}
	}

	/**
	 * Checks for mergeable remote beliefs, and if found merge and update own
	 * belief.
	 * 
	 * @return True if peer Belief was updated, false otherwise.
	 */
	protected boolean maybeMergeBeliefs() {
		try {
			// First get the set of new beliefs for merging
			Belief[] beliefs;
			synchronized (newBeliefs) {
				int n = newBeliefs.size();
				beliefs = new Belief[n];
				int i = 0;
				for (AccountKey addr : newBeliefs.keySet()) {
					try {
						beliefs[i++] = newBeliefs.get(addr).getValue();
					} catch (Exception e) {
						log.warning(e.getMessage());
						// Should ignore belief.
					}
				}
				newBeliefs.clear();
			}
			Peer newPeer = peer.mergeBeliefs(beliefs);
			if (newPeer.getBelief() == peer.getBelief()) return false;

			log.log(LEVEL_BELIEF, "New merged Belief update: " + newPeer.getBelief().getHash().toHexString());
			// we merged successfully, so clear pending beliefs and update Peer
			peer = newPeer;
			return true;
		} catch (MissingDataException e) {
			// Shouldn't happen if beliefs are persisted
			// e.printStackTrace();
			throw new Error("Missing data in belief update: " + e.getMissingHash().toHexString(), e);
		} catch (BadSignatureException e) {
			// Shouldn't happen if Beliefs are already validated
			// e.printStackTrace();
			throw new Error("Bad Signature in belief update!", e);
		} catch (InvalidDataException e) {
			// Shouldn't happen if Beliefs are already validated
			// e.printStackTrace();
			throw new Error("Invalid data in belief update!", e);
		}
	}
	
	private void processStatus(Message m) {
		try {
			// We can ignore payload

			Connection pc = m.getPeerConnection();
			log.info("Processing status request from: " + pc.getRemoteAddress());
			// log.log(LEVEL_MESSAGE, "Processing query: " + form + " with address: " +
			// address);
			
			Peer peer=this.getPeer();
			Hash beliefHash=peer.getSignedBelief().getHash();
			Hash stateHash=peer.getStates().getHash();

			AVector<ACell> reply=Vectors.of(beliefHash,stateHash);
			
			pc.sendResult(m.getID(), reply);
		} catch (Throwable t) {
			log.warning("Status Request Error: " + t);
		}
	}

	private void processQuery(Message m) {
		try {
			// query is a vector [id , form, address?]
			AVector<ACell> v = m.getPayload();
			CVMLong id = (CVMLong) v.get(0);
			ACell form = v.get(1);

			// extract the Address, or use HERO if not available.
			Address address = (Address) v.get(2);

			Connection pc = m.getPeerConnection();
			log.info("Processing query: " + form + " with address: " + address);
			// log.log(LEVEL_MESSAGE, "Processing query: " + form + " with address: " +
			// address);
			Context<ACell> result = peer.executeQuery(form, address);
			boolean resultReturned;

			if (result.isExceptional()) {
				AExceptional err = result.getExceptional();
				ACell code = err.getCode();
				ACell message = err.getMessage();

				resultReturned = pc.sendResult(id, message, code);
			} else {
				resultReturned = pc.sendResult(id, result.getResult());
			}

			if (!resultReturned) {
				log.warning("Failed to send query result back to client with ID: " + id);
			}

		} catch (Throwable t) {
			log.warning("Query Error: " + t);
		}
	}

	private void processData(Message m) {
		ACell payload = m.getPayload();

		// TODO: be smarter about this? hold a per-client queue for a while?
		Ref<?> r = Ref.get(payload);
		r = r.persistShallow();
		Hash payloadHash = r.getHash();

		log.log(LEVEL_DATA, () -> "Processing DATA of type: " + Utils.getClassName(payload) + " with hash: "
				+ payloadHash.toHexString() + " and encoding: " + Format.encodedBlob(payload).toHexString());

		// if our data satisfies a missing data object, need to process it
		maybeProcessPartial(r.getHash());
	}

	/**
	 * Process an incoming message that represents a Belief
	 * 
	 * @param m
	 */
	private void processBelief(Message m) {
		Connection pc = m.getPeerConnection();
		if (pc.isClosed()) return; // skip messages from closed peer

		ACell o = m.getPayload();

		Ref<ACell> ref = Ref.get(o);
		try {
			// check we can persist the new belief
			ref = ref.persist();

			@SuppressWarnings("unchecked")
			SignedData<Belief> signedBelief = (SignedData<Belief>) o;
			signedBelief.validateSignature();

			synchronized (newBeliefs) {
				AccountKey addr = signedBelief.getAccountKey();
				SignedData<Belief> current = newBeliefs.get(addr);
				if ((current == null) || (current.getValueUnchecked().getTimestamp() >= signedBelief.getValueUnchecked()
						.getTimestamp())) {
					newBeliefs.put(addr, signedBelief);
					
					// Notify the update thread that there is something new to handle
					newThings=true;
				}
			}
			log.log(LEVEL_BELIEF, "Valid belief received by peer at " + getHostAddress() + ": "
					+ signedBelief.getValue().getHash().toHexString());
		} catch (ClassCastException e) {
			// bad message?
			log.warning("Bad message from peer? " + e.getMessage());
		} catch (BadSignatureException e) {
			// we got sent a bad signature.
			// TODO: Probably need to slash peer? but ignore for now
			log.warning("Bad signed belief from peer: " + Utils.ednString(o));
		}
	}

	/*
	 * Runnable class acting as a peer worker. Handles messages from the receive
	 * queue from known peers
	 */
	private Runnable receiverLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(getStore()); // ensure the loop uses this Server's store

			try {
				log.log(LEVEL_SERVER, "Reciever thread started for peer at " + getHostAddress());

				while (running) { // loop until server terminated
					Message m = receiveQueue.poll(100, TimeUnit.MILLISECONDS);
					if (m != null) {
						processMessage(m);
					}
				}

				log.log(LEVEL_SERVER, "Reciever thread terminated normally for peer " + this);
			} catch (InterruptedException e) {
				log.log(LEVEL_SERVER, "Receiver thread interrupted ");
			} catch (Throwable e) {
				log.severe("Receiver thread terminated abnormally! ");
				log.severe("Server FAILED: " + e.getMessage());
				e.printStackTrace();
			} finally {
				// clear thread from Server as we terminate
				receiverThread = null;
			}
		}
	};

	/*
	 * Runnable loop for managing Server state updates
	 */
	private Runnable updateLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(getStore()); // ensure the loop uses this Server's store

			try {
				// short initial sleep before we start managing connections. Give stuff time to
				// ramp up.
				Thread.sleep(10);

				// loop while the server is running
				while (running) {
					// Update Peer timestamp first. This determines what we might accept.
					peer = peer.updateTimestamp(Utils.getCurrentTimestamp());

					// Try belief update
					maybeUpdateBelief();
					
					// Maybe sleep a bit, wait for some belief updates to accumulate
					if (newThings) {
						newThings=false;
					} else {
						try {
							Thread.sleep(SERVER_UPDATE_PAUSE);
						} catch (InterruptedException e) {
							// continue
						}
					}
				}
			} catch (Throwable e) {
				log.severe("Unexpected exception in server update loop: " + e.toString());
				log.severe("Terminating Server update");
				e.printStackTrace();
			} finally {
				// clear thread from Server as we terminate
				updateThread = null;
			}
		}
	};

	private void reportTransactions(Block block, BlockResult br) {
		// TODO: consider culling old interests after some time period
		int nTrans = block.length();
		for (long j = 0; j < nTrans; j++) {
			SignedData<ATransaction> t = block.getTransactions().get(j);
			Hash h = t.getHash();
			Message m = interests.get(h);
			if (m != null) {
				try {
					log.info("Returning transaction result to " + m.getPeerConnection().getRemoteAddress());

					Connection pc = m.getPeerConnection();
					if ((pc == null) || pc.isClosed()) continue;
					ACell id = m.getID();
					Result res = br.getResults().get(j).withID(id);
					pc.sendResult(res);
				} catch (Throwable e) {
					System.err.println("Error sending Result: " + e.getMessage());
					e.printStackTrace();
					// ignore
				}
				interests.remove(h);
			}
		}
	}

	public int getPort() {
		return nio.getPort();
	}

	@Override
	public void finalize() {
		close();
	}

	/**
	 * Writes the Peer data to the configured store.
	 * 
	 * This will overwrite the previously persisted peer state.
	 */
	public void persistPeerData() {
		AStore tempStore = Stores.current();
		try {
			Stores.setCurrent(store);
			ACell peerData = peer.toData();
			Ref<?> peerRef = ACell.createPersisted(peerData);
			Hash peerHash = peerRef.getHash();
			store.setRootHash(peerHash);
			log.info("Stored peer data for Server: " + peerHash.toHexString());
		} catch (Throwable e) {
			log.severe("Failed to persist peer state when closing server: " + e.getMessage());
		} finally {
			Stores.setCurrent(tempStore);
		}
	}

	@Override
	public synchronized void close() {
		// persist peer state if necessary
		if ((peer != null) && Utils.bool(config.get(Keywords.PERSIST))) {
			persistPeerData();
		}

		running = false;
		if (updateThread != null) updateThread.interrupt();
		if (receiverThread != null) receiverThread.interrupt();
		nio.close();
		// Note we don't do store.close(); because we don't own the store.
	}

	/**
	 * Gets the host address for this Server (including port), or null if closed
	 * 
	 * @return Host Address
	 */
	public InetSocketAddress getHostAddress() {
		return nio.getHostAddress();
	}

	/**
	 * Returns the Keypair for this peer server
	 * 
	 * SECURITY: Be careful with this!
	 */
	private AKeyPair getKeyPair() {
		return (AKeyPair) config.get(Keywords.KEYPAIR);
	}

	/**
	 * Gets the public key of the peer account
	 * 
	 * @return AccountKey of this Peer
	 */
	public AccountKey getAddress() {
		AKeyPair kp = getKeyPair();
		if (kp == null) return null;
		return kp.getAccountKey();
	}

	/**
	 * Connects this Peer to a target Peer, adding the Connection to this Server's
	 * Manager
	 * 
	 * @param hostAddress
	 * @return The newly created connection
	 * @throws IOException
	 */
	public Connection connectToPeer(InetSocketAddress hostAddress) throws IOException {
		Connection pc = Connection.connect(hostAddress, peerReceiveAction, getStore());
		manager.addConnection(pc);
		return pc;
	}

	public AStore getStore() {
		return store;
	}
}

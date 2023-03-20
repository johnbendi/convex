package convex.peer;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Belief;
import convex.core.Peer;
import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.net.message.Message;

/**
 * Component class to handle propagation of new Beliefs from a Peer
 */
public class BeliefPropagator {

	protected final Server server;
	
	static final Logger log = LoggerFactory.getLogger(BeliefPropagator.class.getName());

	public BeliefPropagator(Server server) {
		this.server=server;
	}
	
	protected final Runnable beliefPropagatorLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(server.getStore());
			while (server.isLive()) {
				try {
					// wait until the thread is notified of new work
					synchronized(BeliefPropagator.this) {BeliefPropagator.this.wait(1000);};
					Peer peer=latestPeer;
					latestPeer=null;
					doBroadcastBelief(peer);
				}catch (InterruptedException e) {
					log.trace("Belief Propagator thread interrupted on "+server);
				}
			}
		}
	};
	
	protected final Thread beliefPropagatorThread=new Thread(beliefPropagatorLoop);
	
	/**
	 * Time of last belief broadcast
	 */
	long lastBroadcastBelief=0;
	private long beliefBroadcastCount=0L;

	private Peer latestPeer;
	
	public long getBeliefBroadcastCount() {
		return beliefBroadcastCount;
	}
	
	public synchronized void broadcastBelief(Peer peer) {
		this.latestPeer=peer;
		notify();
	}
	
	private void doBroadcastBelief(Peer peer) {
		if (peer==null) return;

		Belief belief=peer.getBelief();
		
		// At this point we know something updated our belief, so we want to rebroadcast
		// belief to network
		Consumer<Ref<ACell>> noveltyHandler = r -> {
			ACell o = r.getValue();
			if (o == belief) return; // skip sending data for belief cell itself, will be BELIEF payload
			Message msg = Message.createData(o);
            // broadcast to all peers trusted or not
			server.manager.broadcast(msg, false);
		};

		// persist the state of the Peer, announcing the new Belief
		// (ensure we can handle missing data requests etc.)
		peer=peer.persistState(noveltyHandler);

		// Broadcast latest Belief to connected Peers
		SignedData<Belief> sb = peer.getSignedBelief();

		Message msg = Message.createBelief(sb);

		server.manager.broadcast(msg, false);
		lastBroadcastBelief=Utils.getCurrentTimestamp();
		beliefBroadcastCount++;
	}

	public void close() {
		beliefPropagatorThread.interrupt();
	}

	public void start() {
		// TODO Auto-generated method stub
		beliefPropagatorThread.start();
	}
}
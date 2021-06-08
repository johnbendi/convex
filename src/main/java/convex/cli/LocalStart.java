package convex.cli;

import java.util.logging.Logger;

import convex.cli.peer.PeerManager;
import convex.core.Init;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/*
 * 		local start command
 *
 *		convex.local.start
 *
 */

@Command(name="start",
	aliases={"st"},
	mixinStandardHelpOptions=true,
	description="Starts a local convex test network.")
public class LocalStart implements Runnable {

	private static final Logger log = Logger.getLogger(LocalStart.class.getName());

	@ParentCommand
	private Local localParent;

	@Option(names={"--count"},
		defaultValue = ""+Init.NUM_PEERS,
		description="Number of local peers to start this can be from 1 to ${DEFAULT-VALUE} peers. Default: ${DEFAULT-VALUE}")
	private int count;

	@Override
	public void run() {
		Main mainParent = localParent.mainParent;
		PeerManager peerManager = PeerManager.create(mainParent.getSessionFilename());
		
		// TODO: Parse peer config
		// Map<Keyword,Object> peerConfig=new HashMap<>();

		if (count > Init.NUM_PEERS) {
			log.severe("Number of peers " + count + " is greater than " + Init.NUM_PEERS);
		}
		log.info("Starting local network with "+count+" peer(s)");
		peerManager.launchLocalPeers(count, Init.KEYPAIRS, Init.FIRST_PEER);
		peerManager.waitForPeers();
	}
}

package convex.cli.peer;

import java.util.HashMap;
import java.util.List;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.init.Init;
import convex.peer.API;
import convex.peer.Server;
import etch.EtchStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * Peer genesis command
 */
@Command(
	name = "genesis",
	mixinStandardHelpOptions = true, 
	description = "Instantiate a Convex network.")
public class PeerGenesis extends APeerCommand {

	@ParentCommand
	private Peer peerParent;

	@Spec
	CommandSpec spec;
	
	@Option(names = { "--governance-key" }, 
			defaultValue = "${env:CONVEX_GOVERNANCE_KEY}", 
			scope = ScopeType.INHERIT, 
			description = "Network Governance Key. Must be a valid Ed25519 public key. Genesis key will be used if not specified (unless security is strict).")
	protected String governanceKey;

	@Override
	public void run() {
		storeMixin.loadKeyStore();

		AKeyPair peerKey = ensurePeerKey();
		AKeyPair genesisKey=ensureControllerKey();
		
		AccountKey govKey=AccountKey.parse(governanceKey);
		if (govKey==null) {
			paranoia("--goverannce-key must be specified in strict security mode");
			if (governanceKey==null) {
				govKey=genesisKey.getAccountKey();
			} else {
				throw new CLIError(ExitCodes.DATAERR,"Unable to parse --governance-key argument. Should be a 32-byte hex key.");
			}
		}

		EtchStore store=getEtchStore();
		
		State genesisState=Init.createState(List.of(peerKey.getAccountKey()));
		inform("Created genesis state with hash: "+genesisState.getHash());
		
		inform(2,"Testing genesis state peer initialisation");
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE, store);
		config.put(Keywords.STATE, genesisState);
		config.put(Keywords.KEYPAIR, peerKey);
		Server s=API.launchPeer(config);
		s.close();
		informSuccess("Convex genesis succeeded!");
	}
}

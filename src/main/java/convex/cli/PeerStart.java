package convex.cli;

import java.io.File;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.logging.Logger;


import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.store.AStore;
import etch.EtchStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/*
	*  peer start command
	*
*/

@Command(name="start",
	mixinStandardHelpOptions=true,
	description="Starts one or more peer servers.")
public class PeerStart implements Runnable {

	private static final Logger log = Logger.getLogger(PeerStart.class.getName());

	@ParentCommand
	private Peer peerParent;

	@Spec CommandSpec spec;

	@Option(names={"-i", "--index"},
		defaultValue="-1",
		description="Keystore index of the public/private key to use for the peer.")
	private String keystoreIndex;

	@Option(names={"--public-key"},
		defaultValue="",
		description="Hex string of the public key in the Keystore to use for the peer. You only need to enter in the first distinct hex values of the public key. e.g. 0xf0234 or f0234")
	private String keystorePublicKey;

	@Option(names={"-r", "--reset"},
		description="Reset and delete the etch database if it exists. Default: ${DEFAULT-VALUE}")
	private boolean isReset;

	@Override
	public void run() {

		Main mainParent = peerParent.mainParent;
		AKeyPair keyPair = null;
		int port = 0;
		int index = Integer.parseInt(keystoreIndex);
		String publicKeyClean = keystorePublicKey.toLowerCase().replaceAll("^0x", "");

		String password = mainParent.getPassword();

		if (password == null || password.isEmpty()) {
			log.severe("You need to provide a keystore password");
			return;
		}

		if ( publicKeyClean.isEmpty() && index <= 0) {
			log.severe("You need to provide a keystore public key identity via the --index or --public-key options");
			return;
		}

		File keyFile = new File(mainParent.getKeyStoreFilename());
		try {
			if (!keyFile.exists()) {
				log.severe("Cannot find keystore file "+keyFile.getCanonicalPath());
				return;
			}
			log.info("reading keystore file: "+keyFile.getPath());
			KeyStore keyStore = PFXTools.loadStore(keyFile, password);

			int counter = 1;
			Enumeration<String> aliases = keyStore.aliases();

			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				if (counter == index || alias.indexOf(publicKeyClean) == 0) {
					keyPair = PFXTools.getKeyPair(keyStore, alias, password);
					break;
				}
				counter ++;
			}
		} catch (Throwable t) {
			System.out.println("Cannot load key store "+t);
			t.printStackTrace();
		}

		if (keyPair==null) {
			log.severe("Cannot find key in keystore");
			return;
		}

		if (mainParent.getPort()!=0) {
			port = Math.abs(mainParent.getPort());
		}

		try {
			AStore store = null;
			String etchStoreFilename = mainParent.getEtchStoreFilename();
			if (etchStoreFilename != null && !etchStoreFilename.isEmpty()) {
				File etchFile = new File(etchStoreFilename);
				if ( isReset && etchFile.exists()) {
					log.info("reset: removing old etch storage file " + etchStoreFilename);
					etchFile.delete();
				}
				store = EtchStore.create(etchFile);
			}
			log.info("Starting peer");
			peerParent.launchPeer(keyPair, port, store);
			peerParent.waitForPeers();
		} catch (Throwable t) {
			System.out.println("Unable to launch peer "+t);
		}
	}
}

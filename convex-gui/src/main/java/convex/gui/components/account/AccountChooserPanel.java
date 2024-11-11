package convex.gui.components.account;

import java.awt.event.ItemEvent;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.cvm.ops.Special;
import convex.core.data.Address;
import convex.core.exceptions.ResultException;
import convex.gui.components.BalanceLabel;
import convex.gui.components.DropdownMenu;
import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Panel allowing the selection of account and query mode for a Convex connection
 */
@SuppressWarnings("serial")
public class AccountChooserPanel extends JPanel {

	private JComboBox<String> modeCombo;
	public final AddressCombo addressCombo;
	public final KeyPairCombo keyCombo;

	private BalanceLabel balanceLabel;
	
	protected Convex convex;

	public AccountChooserPanel(Convex convex) {
		this.convex=convex;
		
		MigLayout layout = new MigLayout();
		setLayout(layout);

		{
			JPanel mp=new JPanel();
			mp.setLayout(new MigLayout());

			// Account selection
			mp.add(new JLabel("Account:"));
	
			Address address=convex.getAddress();
			addressCombo = new AddressCombo(address);
			addressCombo.setToolTipText("Select Account for use");
			mp.add(addressCombo);
			
			keyCombo=KeyPairCombo.forConvex(convex);
			keyCombo.setToolTipText("Select a key pair from your Keyring. This will be used to sign transactions.");
			keyCombo.addItemListener(e->{
				if (e.getStateChange()==ItemEvent.DESELECTED) {
					// key pair was deselected and/or set to null
					convex.setKeyPair(null);
					return;
				};
				AWalletEntry we=(AWalletEntry)e.getItem();
				if (we==null) {
					convex.setKeyPair(null);
				} else {
					AKeyPair kp;
					if (we.isLocked()) {
						String s=JOptionPane.showInputDialog(AccountChooserPanel.this,"Enter password to unlock wallet:\n"+we.getPublicKey());
						if (s==null) {
							return;
						}
						char[] pass=s.toCharArray();
						boolean unlock=we.tryUnlock(s.toCharArray());
						if (!unlock) {
							return;
						}
						kp=we.getKeyPair();
						we.lock(pass);
					} else {
						kp=we.getKeyPair();
					}
					convex.setKeyPair(kp);
				}
			});
			mp.add(keyCombo);

	
			addressCombo.addItemListener(e -> {
				updateAddress(addressCombo.getAddress());
			});
			
			// Balance Info
			mp.add(new JLabel("Balance: "));
			balanceLabel = new BalanceLabel();
			balanceLabel.setToolTipText("Convex Coin balance of the currently selected Account");
			mp.add(balanceLabel);
			updateBalance(getAddress());

			add(mp,"dock west");
		}
		
		// Settings
		{
			//////////////////////////////////
			// Settings Popup menu for peer
			JPopupMenu popupMenu = new JPopupMenu();

			JMenuItem clearSeqButton = new JMenuItem("Clear sequence",Toolkit.menuIcon(0xe9d5));
			clearSeqButton.addActionListener(e -> {
				convex.clearSequence();
			});
			popupMenu.add(clearSeqButton);
			
			JMenuItem setSeqButton = new JMenuItem("Set sequence...",Toolkit.menuIcon(0xe6d4));
			setSeqButton.addActionListener(e -> {
				try {
					long seq=convex.getSequence();
					String s = JOptionPane.showInputDialog(this, "Current sequence number is "+seq+", so the next sequence number expected is "+(seq+1), "Enter next sequence number", JOptionPane.QUESTION_MESSAGE);
					if (s==null) return;
					
					long nextSeq=Long.parseLong(s);
					
					convex.setNextSequence(nextSeq);
					System.err.println("Sequence number set: "+convex.getSequence());
				} catch (NumberFormatException e1) {
					JOptionPane.showMessageDialog(this, "Invalid sequence number");
				}catch (ResultException e1) {
					e1.printStackTrace();
				} catch (InterruptedException e1) {
					Thread.currentThread().interrupt();
				}
				convex.clearSequence();
			});
			popupMenu.add(setSeqButton);


			DropdownMenu dm = new DropdownMenu(popupMenu,Toolkit.SMALL_ICON_SIZE);
			add(dm);
		}
		
		// Mode selection	
		{
			JPanel mp=new JPanel();
			mp.setLayout(new MigLayout());
			mp.setBorder(null);
			modeCombo = new JComboBox<String>();
			modeCombo.setToolTipText("Use Transact to execute transactions (uses Convex Coins).\n\n"
					+ "Use Query to compute results without changing on-chain state (free).\n\n"
					+ "Use Prepare to prepare the transcation without submitting."
					);
			modeCombo.addItem("Transact");
			modeCombo.addItem("Query");
			modeCombo.addItem("Prepare");
			// modeCombo.addItem("Prepare...");
			if (convex.getKeyPair()==null) modeCombo.setSelectedItem("Query");
			mp.add(modeCombo);
			add(mp, "dock east");
		}
	}

	public Address getAddress() {
		return convex.getAddress();
	}
	
	public void updateAddress(Address a) {
		convex.setAddress(a);
		
		updateBalance();
	}
	
	public void updateBalance() {
		updateBalance(getAddress());
	}

	private void updateBalance(Address a) {
		try {
			convex.query(Special.get("*balance*"),a).thenAccept(r-> {
				balanceLabel.setFromResult(r);
			});
		} catch (NullPointerException t) {
			balanceLabel.setText(t.getClass().getName());
		}
	}

	public String getMode() {
		return (String) modeCombo.getSelectedItem();
	}

	public Convex getConvex() {
		return convex;
	}
}

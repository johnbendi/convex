package convex.gui.components;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.text.JTextComponent;

import convex.gui.utils.SymbolIcon;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial") 
public class RightCopyMenu extends JPopupMenu implements ActionListener {

	JMenuItem copyMenuItem = new JMenuItem("Copy",SymbolIcon.get(0xe14d,Toolkit.SMALL_ICON_SIZE));
	JMenuItem cutMenuItem = new JMenuItem("Cut",SymbolIcon.get(0xf08b,Toolkit.SMALL_ICON_SIZE));
    JMenuItem pasteMenuItem = new JMenuItem("Paste",SymbolIcon.get(0xe14f,Toolkit.SMALL_ICON_SIZE));
    
    JTextComponent comp;
    
	public RightCopyMenu(JTextComponent invoker) {
		this.comp=invoker;
		copyMenuItem.addActionListener(this);
		cutMenuItem.addActionListener(this);
		pasteMenuItem.addActionListener(this);
	        
		add(copyMenuItem);
		if (invoker.isEditable()) {	
			add(cutMenuItem);
			add(pasteMenuItem);
		}
		
		setInvoker(invoker);
	}
	
	public static void addTo(JTextComponent tf) {
		RightCopyMenu menu=new RightCopyMenu(tf);
		
		tf.addMouseListener(new MouseAdapter() {
			public void mousePressed(MouseEvent e) {
                switch(e.getButton()) {
                    case MouseEvent.BUTTON3: {                    	
                        menu.show(tf, e.getX(), e.getY());
                        break;
                    }
                }
            }			
		});
	}

	@Override
	public void actionPerformed(ActionEvent e) {
	     Object source = e.getSource();
	     Component invoker=getInvoker();
	     if (invoker instanceof JTextComponent) {
	    	 JTextComponent tf = (JTextComponent)invoker;
	    	 if (source == cutMenuItem) {
	    		 tf.cut();
	    	 } else if (source == copyMenuItem) {
	    		 tf.copy();
	    	 } else if (source == pasteMenuItem) {
	    		 tf.paste();
	    	 }		
	     }
	}
}

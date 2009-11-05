/*
 * Copyright (c) 2009, SQL Power Group Inc.
 *
 * This file is part of Wabit.
 *
 * Wabit is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Wabit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package ca.sqlpower.wabit.swingui.enterprise;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.commons.codec.binary.Base64;
import org.json.JSONObject;

import ca.sqlpower.swingui.DataEntryPanel;
import ca.sqlpower.util.Version;
import ca.sqlpower.wabit.WabitVersion;
import ca.sqlpower.wabit.enterprise.client.WabitServerInfo;

import com.jgoodies.forms.builder.DefaultFormBuilder;
import com.jgoodies.forms.layout.FormLayout;

/**
 * Creates a panel for setting the properties of a WabitServerInfo. Since
 * instances of WabitServerInfo are not mutable, calling applyChanges() will not
 * modify the original WabitServerInfo object provided in the constructor. You
 * must obtain a new WabitServerInfo object by calling getServerInfo().
 */
public class ServerInfoPanel implements DataEntryPanel {

    private final Component dialogOwner;

    private final JPanel panel;

    private JTextField name;
    private JTextField host;
    private JTextField port;
    private JTextField path;
    private JTextField username;
    private JPasswordField password;
    private JButton testButton;

    
    public ServerInfoPanel(Component dialogOwner, WabitServerInfo defaultSettings) {
        this.dialogOwner = dialogOwner;
        panel = buildUI(defaultSettings);
    }

    public ServerInfoPanel(JComponent dialogOwner) {
        this(dialogOwner, new WabitServerInfo("", "", 8080, "/wabit-enterprise/", "", ""));
    }

    private JPanel buildUI(WabitServerInfo si) {
        DefaultFormBuilder builder = new DefaultFormBuilder(new FormLayout("pref, 4dlu, max(100dlu; pref):grow"));
        
        builder.append("Display Name", name = new JTextField(si.getName()));
        builder.append("Host", host = new JTextField(si.getServerAddress()));
        builder.append("Port", port = new JTextField(String.valueOf(si.getPort())));
        builder.append("Path", path = new JTextField(si.getPath()));
        builder.append("Username", username = new JTextField(si.getUsername()));
        builder.append("Password", password = new JPasswordField(si.getPassword()));
        
        builder.appendSeparator();
        
        builder.append("", testButton = new JButton("Test connection"));
        this.testButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				HttpURLConnection conn = null;
				InputStream is = null;
				ByteArrayOutputStream baos = null;
				try {
					// Build the base URL
					StringBuilder sb = new StringBuilder();
					sb.append("http://");
					sb.append(host.getText());
					sb.append(":");
					sb.append(port.getText());
					sb.append(path.getText());
					sb.append(path.getText().endsWith("/")?"workspaces":"/workspaces");
					
					// Spawn a connection object
					URL url = new URL(sb.toString());
					conn = (HttpURLConnection)url.openConnection();
					conn.setRequestMethod("OPTIONS");
					conn.setDoInput(true);
					
					// Add credentials
					String hash = new String(
						Base64.encodeBase64(
							username.getText()
								.concat(":")
								.concat(new String(password.getPassword())).getBytes()));
					conn.setRequestProperty(
							"Authorization", "Basic " + hash);

					// Get the response
					conn.connect();
					is = conn.getInputStream();
					baos = new ByteArrayOutputStream();
					byte[] buf = new byte[1024];
					int count;
					while ((count = is.read(buf)) > 0) {
						baos.write(buf, 0, count);
					}
					
					// Decode the message
					JSONObject jsonObject = new JSONObject(baos.toString());
					String version = jsonObject.getString("server.version");
					String key = jsonObject.getString("server.key");
					
					// Validate versions
					Version serverVersion = new Version(version);
					Version clientVersion = WabitVersion.VERSION;
					if (!serverVersion.equals(clientVersion)) {
						JOptionPane.showMessageDialog(
		    				dialogOwner, 
		    				"The server does not use the same Wabit version as your client software.\n"
		    					.concat("Server version is ")
		    					.concat(serverVersion.toString())
		    					.concat(" while your client version is ")
		    					.concat(clientVersion.toString())
		    					.concat("\nWe recommend using the same version as the server to prevent communication errors."),
		    				"Different versions detected", 
		    				JOptionPane.WARNING_MESSAGE);
					}
				} catch (Exception ex) {
					if (ex.getMessage().contains("401")) {
						// Auth exception
						JOptionPane.showMessageDialog(
		    				dialogOwner, 
		    				"It appears that the username and password you provided are not valid."
		    					.concat("\nPlease verify the provided values or contact your system administrator."),
		    				"Authentication failed", 
		    				JOptionPane.ERROR_MESSAGE);
					} else {
						// Generic message
						JOptionPane.showMessageDialog(
		    				dialogOwner, 
		    				"There was an error while trying to reach the Wabit server : "
		    					.concat(ex.getLocalizedMessage()),
		    				"Test failed", 
		    				JOptionPane.ERROR_MESSAGE);
					}
				} finally {
					try {
						if (is != null) is.close();
					} catch (IOException e2) {
						// no op
					}
					try {
						if (baos != null) baos.close();
					} catch (IOException e1) {
						// no op
					}
				}
			}
		});
        
        return builder.getPanel();
    }

    /**
     * Returns a new WabitServerInfo object which has been configured based on the
     * settings currently in this panel's fields.
     */
    public WabitServerInfo getServerInfo() {
        int port = Integer.parseInt(this.port.getText());
        WabitServerInfo si = new WabitServerInfo(
                name.getText(), host.getText(), port, path.getText(), 
                username.getText(), new String(password.getPassword()));
        return si;
    }
    public JComponent getPanel() {
        return panel;
    }

    /**
     * Checks fields for validity, but does not modify the WabitServerInfo given in
     * the constructor (this is not possible because it's immutable). If any of
     * the fields contain inappropriate entries, the user will be told so in a
     * dialog.
     * 
     * @return true if all the fields contain valid values; false if there are
     *         invalid fields.
     */
    public boolean applyChanges() {
    	
    	if (this.name.getText()==null||this.name.getText().equals("")) {
    		JOptionPane.showMessageDialog(
    				dialogOwner, "Please give this conenction a name for future reference.",
    				"Name Required", JOptionPane.ERROR_MESSAGE);
    		return false;
    	}
    	
    	String port = this.port.getText();
    	try {
    		Integer.parseInt(port);
    	} catch (NumberFormatException ex) {
    		JOptionPane.showMessageDialog(
    				dialogOwner, "The server port must be a numeric value. It is usually either 80 or 8080. In doubt, contact your system administrator.",
    				"Invalid Server Port Number", JOptionPane.ERROR_MESSAGE);
    		return false;
    	}
    	
    	if (!this.path.getText().startsWith("/")) {
    		this.path.setText("/".concat(this.path.getText()==null?"":this.path.getText()));
    	}
    	String path = this.path.getText();
    	if (path == null || path.length() < 2) {
    		JOptionPane.showMessageDialog(
    				dialogOwner, "Path must begin with /",
    				"Invalid Setting", JOptionPane.ERROR_MESSAGE);
    		return false;
    	}
    	
    	if (this.host.getText().startsWith("http://")) {
    		this.host.setText(this.host.getText().replace("http://", ""));
    	}
    	String host = this.host.getText();
    	try {
    		new URI("http", null, host, Integer.parseInt(port), path, null, null);
    	} catch (URISyntaxException e) {
    		JOptionPane.showMessageDialog(
    				dialogOwner, "There seems to be a problem with the host name you provided. It can be a web URL (you can omit the http:// part) or a IP adress. Please verify the values provided are correct.",
    				"", JOptionPane.ERROR_MESSAGE);
    		return false;
    	}
    	
        
        
        return true;
    }

    public void discardChanges() {
        // nothing to do
    }

    public boolean hasUnsavedChanges() {
        return true;
    }
    

	public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                	ServerInfoPanel panel = new ServerInfoPanel(null);
                	
                    JFrame f = new JFrame("TEST PANEL");
                    JPanel outerPanel = new JPanel(new BorderLayout());
                    outerPanel.setBorder(BorderFactory.createLineBorder(Color.BLUE));
                    outerPanel.add(panel.getPanel(), BorderLayout.CENTER);
                    f.setContentPane(outerPanel);
                    f.pack();
                    f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    f.setVisible(true);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
    }
}

/*
 * Copyright 2003-2008 Tufts University  Licensed under the
 * Educational Community License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 * 
 * http://www.osedu.org/licenses/ECL-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package tufts.vue;

/**
 * Abstract class for all "browse" based VUE data sources, sometimes called
 * "old style", before VUE had OSID integration for accessing data-sources via search.
 *
 * This class is for data-sources where all the content want's to be seen, based
 * on the configuration.  E.g., a local directory, a list of user favorites, a remote FTP
 * site, an RSS feed, etc.
 * 
 * @version $Revision: 1.2 $ / $Date: 2008-09-16 11:55:40 $ / $Author: sfraize $
 * @author  rsaigal
 * @author  sfraize
 */

import tufts.vue.DEBUG;

import java.util.Map;
import java.util.List;
import java.net.URL;
import java.net.URLConnection;
import javax.swing.JComponent;

import edu.tufts.vue.ui.ConfigurationUI;

public abstract class BrowseDataSource implements DataSource
{
    private static final org.apache.log4j.Logger Log = org.apache.log4j.Logger.getLogger(BrowseDataSource.class);

    public static final String AUTHENTICATION_COOKIE_KEY = "url_authentication_cookie";

    //protected static final String JIRA_SFRAIZE_COOKIE = "seraph.os.cookie=LkPlQkOlJlHkHiEpGiOiGjJjFi"; // TODO: test hack -- remove
    
    private String displayName = "(unconfigured)";
    private String address;
    private String authenticationCookie;
    private String Id;
    private boolean isAutoConnect;
    private boolean isIncludedInSearch;
    private int publishMode;

    private volatile JComponent _viewer; // volatile should be overkill, but just in case
    private boolean isAvailable;
    private String hostName;
    
    public BrowseDataSource() {}
    
    public BrowseDataSource(String name) {
        setDisplayName(name);
    }

    /** parameter block that can be used for generating XML in EditLibraryPanel to add fields to the data-source UI */
    public static class ConfigField {
        public String key;
        public String title;
        public String description;
        public String value;
        public int uiControl = ConfigurationUI.SINGLE_LINE_CLEAR_TEXT_CONTROL;
        public int maxLen;

        public ConfigField(String k, String t, String d, String v) {
            key = k;
            title = t;
            description = d;
            value = v;
        }
    }

    // todo: to persist extra properties (e.g., authentication keys) add a getPropertyList for
    // castor that returns PropertyEntry's to persist extra key/values.  Could use PropertyMap and
    // add a convert to list (URLResource just does this manually when requested for persistance by
    // castor) or add a util function. (or, could just hack it into RSS data source)

    /**
     * This handles the default properties "name" and "address" -- implementors should override
     * to add additional properties of their own.  This is used by EditLibraryPanel to
     * pass the result of user property edits back into the VueDataSource.
     */
    // todo: this is really just a bean interface: would be much easier to handle that way
    // using freely availble libraries (e.g., apache), and we could avoid yet enother
    // set of property key/value dispatch code.
    public void setConfiguration(java.util.Properties p) {
        String val = null;
        
        try {
            if ((val = p.getProperty("name")) != null)
                setDisplayName(val);
        } catch (Throwable t) {
            Log.error("val=" + val, t);
        }
        try {
            if ((val = p.getProperty("address")) != null)
                setAddress(val);
        } catch (Throwable t) {
            Log.error("val=" + val, t);
        }
        try {
            if ((val = p.getProperty(AUTHENTICATION_COOKIE_KEY)) != null)
                setAuthenticationCookie(val);
        } catch (Throwable t) {
            Log.error("val=" + val, t);
        }
        
    }

    public java.util.List<ConfigField> getConfigurationUIFields() {
        List<ConfigField> fields = new java.util.ArrayList();

        fields.add(new ConfigField("address",
                                   "Address",
                                   getTypeName() + " URL",
                                   getAddress()));
            
        fields.add(new ConfigField(AUTHENTICATION_COOKIE_KEY,
                                   "Authentication",
                                   "Any required authentication cookie (optional)",
                                   getAuthenticationCookie()));

        return fields;
    }

    public String getTypeName() {
        return getClass().getSimpleName();
    }
    
    public final void setAddress(String newAddress) {
        if (newAddress != null)
            newAddress = newAddress.trim();
        out("setAddress[" + newAddress + "]");
        if (newAddress != null && !newAddress.equals(address)) {
            this.address = newAddress;
            // any time we change the address, rebuild the viewer
            unloadViewer();

            java.net.URI uri;
            try {
                uri = new java.net.URI(newAddress);
                hostName = uri.getHost();
            } catch (Throwable t) {
                hostName = null;
            }
            
        }
    }

    public final String getAddress() {
        return this.address;
    }

    public void setAuthenticationCookie(String s) {
        Log.debug("setAuthenticationCookie[" + s + "]");
        if (s == authenticationCookie || (s != null && s.equals(authenticationCookie))) {
            return;
        } else {
            authenticationCookie = s;
            unloadViewer();
        }
    }
    
    public String getAuthenticationCookie() {
        return authenticationCookie;
    }
    

    /** impl's may override to provide a count of the items in the view
     * @return -1 by defaut
     */
    public int getCount() {
        return -1;
    }

    /** @return a host name if one can be found in the address, otherwise returns the address */
    public String getAddressName() {
        if (hostName != null)
            return hostName;
        else
            return getAddress();
    }

    public String getHostName() {
        return hostName;
    }

    public String getDisplayName() {
        return this.displayName;   
    }
   
    public void setDisplayName(String name) {
        this.displayName = name;
    }
    
    /**
     * @return the JComponent that is current set to displays the content for this data source
     * Will return null until set.
     */
    public final JComponent getResourceViewer() {
        return _viewer;
    }
    
    /** set the viewer that's been loaded */
    // call from AWT only
    void setViewer(JComponent v) {
        if (DEBUG.Enabled && _viewer != v) out("setViewer " + tufts.vue.gui.GUI.name(v));
        _viewer = v;
    }

    // call from AWT only
    protected void unloadViewer() {
        if (DEBUG.DR) out("unloadViewer");
        if (mLoadThread != null)
            setLoadThread(null);
        if (_viewer != null)
            setViewer(null);
        setAvailable(false);
    }


    private Thread mLoadThread;
    
    // call from AWT only
    void setLoadThread(Thread t) {
        if (DEBUG.Enabled) out("setLoadThread: " + t);
        if (mLoadThread != null && mLoadThread.isAlive()) {
            if (DEBUG.Enabled) Log.debug(this + "; setLoadThread: INTERRUPT " + mLoadThread);
            //if (DEBUG.Enabled) Log.warn(this + "; setLoadThread: FALLBACK-INTERRUPT " + mLoadThread);
            mLoadThread.interrupt();
        }
        mLoadThread = t;
    }
    // call from AWT only
    Thread getLoadThread() {
        return mLoadThread;
    }
    
    // call from AWT only
    boolean isLoading() {
        return mLoadThread != null;
    }

    boolean isAvailable() {
        return isAvailable;
    }

    void setAvailable(boolean t) {
        isAvailable = t;
    }
    
    
    
    /**
     * @return build a JComponent that displays the content for this data source
     * This will most likely NOT be called on the AWT thread, so it should
     * only build the component, and not add anything into any live on-screen
     * AWT component hierarchies.
     */
    protected abstract JComponent buildResourceViewer();

    public void setisAutoConnect() {
        this.isAutoConnect = false;
    }
     
    public String getId() {
        return this.Id; 
    }
   
    public void setId(String Id) {
        this.Id = Id;
    }
    
    public int getPublishMode() {
        return this.publishMode;   
    }
   
    public boolean isAutoConnect() {
        return this.isAutoConnect;   
    }
    
    public void setAutoConnect(boolean b) {
        this.isAutoConnect = b;
    }
    
    public boolean isIncludedInSearch() {
        return this.isIncludedInSearch;
    }
	
    public void setIncludedInSearch(boolean included) {
        this.isIncludedInSearch = included;
    }

    protected URLConnection openAddress() {

        String addressText = getAddress();

        Log.debug("openAddress " + addressText);
        
        if (addressText.toLowerCase().startsWith("feed:"))
            addressText = "http:" + addressText.substring(5);
        
        URL address = null;
        
        try {
            address = new URL(addressText);
        } catch (Throwable t) {
            throw new DataSourceException("Bad address in " + getClass().getSimpleName(), t);
        }
        
        Map<String,List<String>> headers = null;

        try {
            if (DEBUG.Enabled) Log.debug("opening " + address);
            URLConnection conn = address.openConnection(); 
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US; rv:1.8.1.8) Gecko/20071008 Firefox/2.0.0.8");
            
            if (getAuthenticationCookie() != null)
                conn.setRequestProperty("Cookie", getAuthenticationCookie());
            
            if (DEBUG.Enabled) Log.debug("request-properties: " + conn.getRequestProperties());
            
            conn.connect();

            if (DEBUG.Enabled) {

                Log.debug("connected; fetching headers [" + conn + "]");
                
                final StringBuilder buf = new StringBuilder(512);
                
                buf.append("headers [" + conn + "];\n");
                
                headers = conn.getHeaderFields();
                
                List<String> response = headers.get(null);
                if (response != null)
                    buf.append(String.format("%20s: %s\n", "HTTP-RESPONSE", response));
                
                for (Map.Entry<String,List<String>> e : headers.entrySet()) {
                    if (e.getKey() != null)
                        buf.append(String.format("%20s: %s\n", e.getKey(), e.getValue()));
                }
                
                Log.debug(buf);
                
            }

            return conn;
            
        } catch (java.io.IOException io) {
            throw new DataSourceException(null, io);
        }
    }
    
    
    @Override
    public final String toString() {
        return getClass().getSimpleName() + "[" + getDisplayName() + "; " + getAddress() + "]";
    }
    
    private void out(String s) {
        Log.debug(getClass().getSimpleName() + "[" + getDisplayName() + "] " + s);
    }


    //private JPanel addDataSourcePanel;
    //private JPanel editDataSourcePanel;
//    public void setAddDataSourcePanel() {
//        this.addDataSourcePanel = new JPanel();
//    }
//    public  JComponent getAddDataSourcePanel(){
//        return this.addDataSourcePanel;   
//    }
       
//    public void setEditDataSourcePanel(){
//        this.editDataSourcePanel = new JPanel();
//    }
//    public JComponent getEditDataSourcePanel(){
//        return this.editDataSourcePanel;  
//    }
   
    
    
        
    
}


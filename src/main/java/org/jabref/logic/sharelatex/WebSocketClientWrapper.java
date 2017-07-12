package org.jabref.logic.sharelatex;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler.Whole;
import javax.websocket.Session;

import org.jabref.JabRefExecutorService;
import org.jabref.logic.importer.ImportFormatPreferences;
import org.jabref.logic.importer.ParseException;
import org.jabref.logic.sharelatex.events.ShareLatexContinueMessageEvent;
import org.jabref.logic.sharelatex.events.ShareLatexEntryMessageEvent;
import org.jabref.logic.sharelatex.events.ShareLatexErrorMessageEvent;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.glassfish.tyrus.ext.extension.deflate.PerMessageDeflateExtension;

public class WebSocketClientWrapper {

    private Session session;
    private String oldContent;
    private int version;
    private int commandCounter;
    private ImportFormatPreferences prefs;
    private String docId;
    private String projectId;
    private String databaseName;
    private final EventBus eventBus = new EventBus("SharelatexEventBus");
    private boolean leftDoc = false;
    private boolean errorReceived = false;

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

    private final ShareLatexParser parser = new ShareLatexParser();
    private String serverOrigin;
    private Map<String, String> cookies;

    public WebSocketClientWrapper() {
        this.eventBus.register(this);
    }

    public void setImportFormatPrefs(ImportFormatPreferences prefs) {
        this.prefs = prefs;
    }

    public void createAndConnect(URI webSocketchannelUri, String projectId, BibDatabaseContext database) {

        try {
            this.projectId = projectId;

            ClientEndpointConfig.Configurator configurator = new MyCustomClientEndpointConfigurator(serverOrigin, cookies);
            final ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().extensions(Arrays.asList(new PerMessageDeflateExtension()))
                    .configurator(configurator).build();
            final CountDownLatch messageLatch = new CountDownLatch(1);

            ClientManager client = ClientManager.createClient();
            client.getProperties().put(ClientProperties.REDIRECT_ENABLED, true);
            client.getProperties().put(ClientProperties.LOG_HTTP_UPGRADE, true);

            ClientManager.ReconnectHandler reconnectHandler = new ClientManager.ReconnectHandler() {

                private final AtomicInteger counter = new AtomicInteger(0);

                @Override
                public boolean onConnectFailure(Exception exception) {
                    final int i = counter.incrementAndGet();
                    if (i <= 3) {
                        System.out.println(
                                "### Reconnecting... (reconnect count: " + i + ") " + exception.getMessage());
                        return true;
                    } else {
                        messageLatch.countDown();
                        return false;
                    }
                }

                @Override
                public long getDelay() {
                    return 0;
                }

            };
            client.getProperties().put(ClientProperties.RECONNECT_HANDLER, reconnectHandler);

            this.session = client.connectToServer(new Endpoint() {

                @Override
                public void onOpen(Session session, EndpointConfig config) {
                    System.out.println("Session is open" + session.isOpen());

                    session.addMessageHandler(String.class, (Whole<String>) message -> {

                        message = parser.fixUTF8Strings(message);
                        System.out.println("Received message: " + message);
                        parseContents(message);
                    });
                }

                @Override
                public void onError(Session session, Throwable t) {

                    t.printStackTrace();
                }

                @Override
                public void onClose(Session session, CloseReason closeReason) {
                    if (errorReceived) {
                        System.out.println("Error received in close session");
                    }

                }
            }, cec, webSocketchannelUri);

            //TODO: Change Dialog
            //TODO: On database change event or on save event send new version
            //TODO: When new db content arrived run merge dialog
            //TODO: Identfiy active database/Name of database/doc Id (partly done)

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void joinProject(String projectId) throws IOException {
        incrementCommandCounter();
        String text = "5:" + commandCounter + "+::{\"name\":\"joinProject\",\"args\":[{\"project_id\":\"" + projectId
                + "\"}]}";
        session.getBasicRemote().sendText(text);
    }

    public void joinDoc(String documentId) throws IOException {
        incrementCommandCounter();
        String text = "5:" + commandCounter + "+::{\"name\":\"joinDoc\",\"args\":[\"" + documentId + "\"]}";
        session.getBasicRemote().sendText(text);
    }

    public void leaveDocument(String documentId) throws IOException {
        incrementCommandCounter();
        String text = "5:" + commandCounter + "+::{\"name\":\"leaveDoc\",\"args\":[\"" + documentId + "\"]}";
        session.getBasicRemote().sendText(text);

    }

    private void sendHeartBeat() throws IOException {
        session.getBasicRemote().sendText("2::");
    }

    public void sendNewDatabaseContent(String newContent) throws InterruptedException {
        queue.put(newContent);
    }

    private void sendUpdateAsDeleteAndInsert(String docId, int position, int version, String oldContent, String newContent) throws IOException {
        ShareLatexJsonMessage message = new ShareLatexJsonMessage();

        List<SharelatexDoc> diffDocs = parser.generateDiffs(oldContent, newContent);
        String str = message.createUpdateMessageAsInsertOrDelete(docId, version, diffDocs);

        System.out.println("Send new update Message");

        session.getBasicRemote().sendText("5:::" + str);
    }

    @Subscribe
    public synchronized void listenToSharelatexEntryMessage(ShareLatexContinueMessageEvent event) {

        JabRefExecutorService.INSTANCE.executeInterruptableTask(() -> {
            try {
                String updatedContent = queue.take();
                if (!leftDoc) {
                    System.out.println("Taken from queue");
                    sendUpdateAsDeleteAndInsert(docId, 0, version, oldContent, updatedContent);

                }
            } catch (IOException | InterruptedException e) {
                // TODO Auto-generated catch block
                Thread.currentThread().interrupt();
                e.printStackTrace();
            }
        });

    }

    //Actual response handling
    private void parseContents(String message) {
        try {

            if (message.contains(":::1")) {

                Thread.currentThread().sleep(300);
                System.out.println("Got :::1. Joining project");

            }
            if (message.contains("2::")) {
                setLeftDoc(false);
                eventBus.post(new ShareLatexContinueMessageEvent());
                sendHeartBeat();

            }

            if (message.endsWith("[null]")) {
                System.out.println("Received null-> Rejoining doc");
                joinDoc(docId);
            }

            if (message.startsWith("[null,{", message.indexOf("+") + 1)) {
                System.out.println("We get a list with all files");
                //We get a list with all files
                Map<String, String> dbWithID = parser.getBibTexDatabasesNameWithId(message);

                setDocID(dbWithID.get("references.bib"));

                System.out.println("DBs with ID " + dbWithID);

                joinDoc(docId);

            }
            if (message.contains("{\"name\":\"connectionAccepted\"}") && (projectId != null)) {

                System.out.println("Joining project");
                Thread.sleep(200);
                joinProject(projectId);

            }

            if (message.contains("[null,[")) {
                System.out.println("Message could be an entry ");

                int version = parser.getVersionFromBibTexJsonString(message);
                setVersion(version);

                String bibtexString = parser.getBibTexStringFromJsonMessage(message);
                setBibTexString(bibtexString);
                List<BibEntry> entries = parser.parseBibEntryFromJsonMessageString(message, prefs);

                System.out.println("Got new entries");
                setLeftDoc(false);

                eventBus.post(new ShareLatexEntryMessageEvent(entries, bibtexString));
                eventBus.post(new ShareLatexContinueMessageEvent());

            }

            if (message.contains("otUpdateApplied")) {
                System.out.println("We got an update");

                leaveDocument(docId);
                setLeftDoc(true);
            }
            if (message.contains("otUpdateError")) {
                String error = parser.getOtErrorMessageContent(message);
                eventBus.post(new ShareLatexErrorMessageEvent(error));
            }
            if (message.contains("0::")) {
                leaveDocAndCloseConn();
            }

        } catch (IOException | ParseException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void setDatabaseName(String bibFileName) {
        this.databaseName = bibFileName;
    }

    public void leaveDocAndCloseConn() throws IOException {
        leaveDocument(docId);
        queue.clear();
        session.close();

    }

    public void setServerNameOrigin(String serverOrigin) {
        this.serverOrigin = serverOrigin;

    }

    public void setCookies(Map<String, String> cookies) {
        this.cookies = cookies;

    }

    public void registerListener(Object listener) {
        eventBus.register(listener);
    }

    public void unregisterListener(Object listener) {
        eventBus.unregister(listener);
    }

    private synchronized void setDocID(String docId) {
        this.docId = docId;
    }

    private synchronized void setVersion(int version) {
        this.version = version;
    }

    private synchronized void setBibTexString(String bibtex) {
        this.oldContent = bibtex;
    }

    private synchronized void incrementCommandCounter() {
        this.commandCounter = commandCounter + 1;
    }

    private synchronized void setLeftDoc(boolean leftDoc) {
        this.leftDoc = leftDoc;
    }

    private synchronized void setErrorReceived(boolean errorReceived) {
        this.errorReceived = errorReceived;
    }

}

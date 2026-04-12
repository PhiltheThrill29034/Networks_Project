package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class AuctionServer{

    private ConcurrentHashMap<String,UserProfile> userDB = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String,ActivePeer> activeSessions = new ConcurrentHashMap<>();
    private LinkedBlockingQueue<AuctionItem> auctionQueue = new LinkedBlockingQueue<>();
    public static void main (String[] args){
        AuctionServer server = new AuctionServer();
        server.startLoop();

    }

    private void startLoop(){

        try (ServerSocket ss = new ServerSocket(5000)){
            System.out.println("[Server] Listening on port 5000...");
            while (true){
                Socket clientSocket = ss.accept();
                new Thread(new ClientHandler(clientSocket,this));
            }
            
        } catch (IOException e){

        }
    }


    public UserProfile getUser(String username) {
        return userDB.get(username);
    }

    public boolean registerUser(UserProfile user) {
        UserProfile existing = userDB.putIfAbsent(user.getName(), user);
        return existing == null; // true = success, false = username taken
    }

    public boolean addActiveSession(ActivePeer session){
        ActivePeer existing = activeSessions.putIfAbsent(session.getTokenId(), session);
        return existing==null;
    }

    public ActivePeer getActivePeer(String tokenId){
        return activeSessions.get(tokenId);
    }

    public synchronized String generateToken() {
        String token;
        do {
            token = String.valueOf(new Random().nextInt(Integer.MAX_VALUE));
        } while (activeSessions.containsKey(token)); // keep trying until unique
        return token;
    }

    public void updateConnectionInfo(ActivePeer peer,String ip,String port){
        peer.setConnectionInfo(ip, port);
    }

    
}
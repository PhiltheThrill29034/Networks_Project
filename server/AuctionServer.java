package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import shared.models.AuctionItem;

public class AuctionServer{

    private ConcurrentHashMap<String,UserProfile> userDB = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String,ActivePeer> activeSessions = new ConcurrentHashMap<>(); //the key is the token_id. the value is the ActivePeer object
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

    public void addToAuctionQueue(AuctionItem item){
        //auctionQueue.add(item); add() throws an exception if we try to add an item to a full queue
        auctionQueue.offer(item); //instead, we use offer, which returns true if the item was added successfully, otherwise it returns false
    }

    public void removePeer(String tokenId){
        if (tokenId==null){
            System.out.println("Token id is null, cannot remove");
            return;
        }

        ActivePeer peer = activeSessions.get(tokenId);
        if (peer!=null){
            int numSellerCount = peer.getNumAuctionsSeller();
            int numBidderCount = peer.getNumAuctionsBidder();

            
            
            UserProfile user = userDB.get(peer.getUsername());
            if (user!=null){
                user.setSellerCount(numSellerCount); //now, we copy the seller and bidder count into our database, because our active sessions are temporary.
                user.setBidderCount(numBidderCount);
            }
        }


        activeSessions.remove(tokenId);
    }

    public boolean isLoggedIn(String username){
        //check if any of the current active users match the given username.
        // if yes, it means they are already logged in.
        return activeSessions.values()
                            .stream()
                            .anyMatch(peer -> peer.getUsername().equals(username)) ; 
                            

    }

    
}
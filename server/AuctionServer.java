package server;

import java.io.IOException;
import java.io.PrintWriter;

import java.net.ServerSocket;
import java.net.Socket;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import shared.models.AuctionItem;

public class AuctionServer{

    private ConcurrentHashMap<String,UserProfile> userDB = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String,ActivePeer> activeSessions = new ConcurrentHashMap<>(); //the key is the token_id. the value is the ActivePeer object
    private LinkedBlockingQueue<AuctionItem> auctionQueue = new LinkedBlockingQueue<>();
    private ConcurrentHashMap<String, String> objectOwnership = new ConcurrentHashMap<>(); // <objectId, tokenId>
    private List<PrintWriter> clientWriters = new CopyOnWriteArrayList<>(); // Το CopyOnWriteArrayList<>() είναι Thread-safe

    public static void main (String[] args){
        AuctionServer server = new AuctionServer();
        server.startAuctionManager();
        server.startLoop();

    }

    private void startLoop(){

        try (ServerSocket ss = new ServerSocket(5000)){
            System.out.println("[Server] Listening on port 5000...");
            while (true){
                Socket clientSocket = ss.accept();
                new Thread(new ClientHandler(clientSocket,this)).start();
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

    private AuctionItem currentAuction = null; //The current active auction.
    private long auctionEndTime = 0;
    private String currentHighestBidderToken = null;

    public void startAuctionManager() {
       //Creates a new auction manager thread that continuously checks for active auctions and manages the auction lifecycle.
        new Thread(() -> {
            while (true) {
                synchronized (this) { //Prevents other threads from modifying the auction state while we are checking it.
                    //If there is no active auction, we check if there are pending auctions in the queue. If yes, we start the next one.
                    if (currentAuction == null && !auctionQueue.isEmpty()) {
                        currentAuction = auctionQueue.poll();
                        System.out.println("[AUCTION_SERVER] Started auction: " + currentAuction.getObjectId() + " of seller (tokenId): " + currentAuction.getSellerTokenId());
                        auctionEndTime = System.currentTimeMillis() + (currentAuction.getDuration() * 1000L);
                        currentHighestBidderToken = null;
                    }
                
                    if (currentAuction != null) {
                        //If there is an active auction, we check if the seller is still connected. If not, we cancel the auction immediately.
                        if (!activeSessions.containsKey(currentAuction.getSellerTokenId())) {
                            System.out.println("[AUCTION_SERVER] Seller disconnected. Cancelling: " + currentAuction.getObjectId() + " auctions.");
                            currentAuction = null; 
                        } else if (System.currentTimeMillis() >= auctionEndTime) {
                            finalizeAuction();
                        }
                    }
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    //Finalizes the current auction by resetting the auction state for the next auction, updating the winner with the buyer's port and announcing it.
    private void finalizeAuction() {
        if (currentAuction != null) {
            System.out.println("[AUCTION_SERVER] Auction for " + currentAuction.getObjectId() + " had ended.");
            String message;

            System.out.println("[FINALIZE] Finalizing " + currentAuction.getObjectId() + " auction.");
            if (currentHighestBidderToken != null) {
                ActivePeer winner = activeSessions.get(currentHighestBidderToken);
                ActivePeer seller = activeSessions.get(currentAuction.getSellerTokenId());

                System.out.println("[FINALIZE] winner token = " + currentHighestBidderToken);

                if (winner != null && seller != null) {
                    // Ενημερώνουμε τους μετρητές
                    winner.incrementBidderCount();
                    seller.incrementSellerCount();
                
                    message = "AUCTION_FINISHED" + "|" + winner.getTokenId() + "|" + 
                                     currentAuction.getObjectId() + "|" + seller.getIp() + "|" + seller.getPort();
                    // Μεταδίδουμε το message σε όλους τους Bidder        
                    broadcast(message);
                    System.out.println("[AUCTION_SERVER] Winner of auction for object " + 
                                       currentAuction.getObjectId() + " is " + winner.getUsername() + "!");
                }

                if (winner == null) {
                    System.out.println("[AUCTION_SERVER] Winner disconnected, auction cancelled or no winner broadcast");
                }

                if (seller == null) {
                    System.out.println("[AUCTION_SERVER] Seller disconnected, auction invalid");
                }

            } else { // Δεν υπάρχει κάποιος αγοραστής

                
                message = "AUCTION_FINISHED_NO_WINNER" + "|" + currentAuction.getObjectId();
                broadcast(message);
            }
            currentAuction = null; 
            currentHighestBidderToken = null;
        }
    }

    // Returns a response string indicating the current auction status.
    public synchronized String getCurrentAuctionResponse() {
        if (currentAuction == null) return "NO_ACTIVE_AUCTION";
        return "CURRENT_AUCTION|" + currentAuction.getObjectId() + "|" + currentAuction.getDescription();
    }

    // Returns the highest bid and the corresponding bidder token for the current auction.
    public synchronized String getAuctionDetailsResponse() {
        if (currentAuction == null) return "[ERROR]|No active auction";
        return "AUCTION_DETAILS|" + currentAuction.getSellerTokenId() + "|" + currentAuction.getHighestBid() + "|" + auctionEndTime;
    }

    public synchronized String processBid(String tokenId, double amount) {
        //Checks if the bid is the highest bid for the current auction. If yes, it updates the current highest bid and bidder token. Otherwise, it returns an error message.
        if (currentAuction == null) return "[ERROR]|No active auction";
        if (amount > currentAuction.getHighestBid()) {
            currentAuction.setHighestBid(amount);
            currentHighestBidderToken = tokenId;
            System.out.println("[DEBUG] highestBidderToken = " + currentHighestBidderToken);
            System.out.println("[DEBUG] highestBid = " + currentAuction.getHighestBid());
            return "NEW_BID|OK|" + amount + "|" + currentAuction.getObjectId();
        }
        return "NEW_BID|ERROR|" + currentAuction.getHighestBid() + "|" + currentAuction.getObjectId();
    }

    public String updateOwner(String objectId, String tokenId) { // Γίνεται με την αγορά ενός object από κάποιον bidder
        if (!objectOwnership.contains(objectId)) return "[ERROR]|No such object exists";
            
        objectOwnership.put(objectId, tokenId);
        return "UPDATED_OWNER";
    }

    public void addClientWriters(PrintWriter out) { // Προσθέτουμε από κάθε ClientHandler το κανάλι επικοινωνίας του (out)
        clientWriters.add(out);
    }

    public void removeClientWriters(PrintWriter out) {
        clientWriters.remove(out);
    }

    public void broadcast(String message) { // Κάνουμε broadcast σε όλους τους Bidders το message
        for (PrintWriter out : clientWriters) {
            out.println(message);
        }
    }

}

package Auction;

import java.io.IOException;
import java.io.PrintWriter;

import java.net.ServerSocket;
import java.net.Socket;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.ArrayList;

import shared.models.AuctionItem;
import shared.models.AuctionState;

public class AuctionServer{

    private int bidders;

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
            System.out.println("[AuctionServer] Listening on port 5000...");
            while (true){
                Socket clientSocket = ss.accept();
                bidders++;
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
            System.out.println("[AuctionServer][removerPeer] Token id is null, cannot remove");
            return;
        }

        ActivePeer peer = activeSessions.get(tokenId);
        if (peer!=null){
            int numSellerCount = peer.getNumAuctionsSeller();
            int numBidderCount = peer.getNumAuctionsBidder();
            double reputation_score = peer.getReputation();

            
            
            UserProfile user = userDB.get(peer.getUsername());
            if (user!=null){
                user.setSellerCount(numSellerCount); //now, we copy the seller and bidder count into our database, because our active sessions are temporary.
                user.setBidderCount(numBidderCount);
                user.setReputationScore(reputation_score);
            }
        }

        activeSessions.remove(tokenId);
        bidders--;

        // Σε περίπτωση που το πλήθος των Bidders είναι ίσος με τις ενεργές δημοπρασίες, σημαίνει ότι δεν υπάρχουν 
        // Bidders για να συμμετάσχουν στις συγκεκριμένες δημοπρασίες
        if (bidders <= 2 && activeSessions.size() == bidders) {
            System.out.println("[AuctionServer][removePeer] Same number of active auctions as bidders. Sending shutdown message");
            broadcast("LAST_BIDDERS");
        } else if (activeSessions.isEmpty()) {
            System.out.println("[AuctionServer][removePeer] All Bidders have logged out");
        }
    }

    public boolean isLoggedIn(String username){
        //check if any of the current active users match the given username.
        // if yes, it means they are already logged in.
        return activeSessions.values()
                            .stream()
                            .anyMatch(peer -> peer.getUsername().equals(username)) ; 
                            

    }

    private ConcurrentHashMap<String, ActiveAuctionSlot> activeAuctions = new ConcurrentHashMap<>(2); //the key is the object ID

    public void startAuctionManager() {
       //Creates a new auction manager thread that continuously checks for active auctions and manages the auction lifecycle.
        new Thread(() -> {
            while (true) {
                synchronized (this) { //Prevents other threads from modifying the auction state while we are checking it.
                    //If there is no active auction, we check if there are pending auctions in the queue. If yes, we start the next one.
                    if (activeAuctions.size() < 2 && !auctionQueue.isEmpty()) {
                        AuctionItem selectedItem = selectNextAuctionItem();
                        if (selectedItem != null) {
                            selectedItem.setState(AuctionState.RUNNING);
                            ActiveAuctionSlot newSlot = new ActiveAuctionSlot(selectedItem);
                            activeAuctions.put(selectedItem.getObjectId(), newSlot);

                            System.out.println("[AuctionServer][startAuctionManager] Started parallel auction slot for item: "
                                    + selectedItem.getObjectId() + " from seller: " + selectedItem.getSellerTokenId());
                        }
                    }

                    List<String> toRemove = new ArrayList<>();
                    for (String objId : activeAuctions.keySet()) {
                        ActiveAuctionSlot slot = activeAuctions.get(objId);

                        if (slot != null) {
                            // Αν ο Seller αποσυνδεθεί, η δημοπρασία ακυρώνεται
                            if (!activeSessions.containsKey(slot.item.getSellerTokenId())) {
                                System.out.println("[AuctionServer][startAuctionManager] Seller disconnected. Cancelling: " + slot.item.getObjectId() + " auction.");
                                broadcast("AUCTION_CANCELLED_SELLER_UNAVAILABLE|" + slot.item.getObjectId());
                                toRemove.add(objId);
                            }
                            // Αν ο χρόνος της δημοπρασίας έληξε
                            else if (System.currentTimeMillis() >= slot.auctionEndTime && slot.item.getState() == AuctionState.RUNNING) {
                                finalizeAuction(slot);
                            }
                        }
                    }
                    toRemove.forEach(activeAuctions::remove);
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    private AuctionItem selectNextAuctionItem() {
        AuctionItem firstItem = auctionQueue.peek();
        if (firstItem == null) return null;

        AuctionItem secondItem = null;
        int index = 0;
        for (AuctionItem item : auctionQueue) {
            if (index == 1) {
                secondItem = item;
                break;
            }
            index++;
        }

        if (secondItem == null) {
            return auctionQueue.poll();
        }

        ActivePeer firstSeller  = activeSessions.get(firstItem.getSellerTokenId());
        ActivePeer secondSeller = activeSessions.get(secondItem.getSellerTokenId());

        double firstRep  = (firstSeller  != null) ? firstSeller.getReputation()  : 0.0;
        double secondRep = (secondSeller != null) ? secondSeller.getReputation() : 0.0;

        System.out.println("[AuctionServer][selectNextAuctionItem] Queue selection -- item1: " + firstItem.getObjectId()
                + " (rep=" + firstRep + ") vs item2: " + secondItem.getObjectId()
                + " (rep=" + secondRep + ")");

        if (firstRep < secondRep) {
            System.out.println("[AuctionServer][selectNextAuctionItem] Skipping " + firstItem.getObjectId()
                    + " in favour of higher-reputation seller: " + secondItem.getObjectId());
            auctionQueue.remove(secondItem);
            return secondItem;
        }
        return auctionQueue.poll();
    }

    //Finalizes the current auction by resetting the auction state for the next auction, updating the winner with the buyer's port and announcing it.
    private void finalizeAuction(ActiveAuctionSlot slot) {
        if (slot != null && slot.item != null) {
            System.out.println("[AuctionServer][finalizeAuction] Auction for " + slot.item.getObjectId() + " has ended.");
            String message;

            System.out.println("[AuctionServer][finalizeAuction] Finalizing " + slot.item.getObjectId() + " auction.");

            if (slot.currentHighestBidderToken != null) {
                ActivePeer winner = activeSessions.get(slot.currentHighestBidderToken);
                ActivePeer seller = activeSessions.get(slot.item.getSellerTokenId());

                System.out.println("[AuctionServer][finalizeAuction] Winner token = " + slot.currentHighestBidderToken);

                if (winner != null && seller != null) {
                    slot.item.setState(AuctionState.PENDING_CONFIRMATION);
                    message = "AUCTION_FINISHED" + "|" + winner.getTokenId() + "|" +
                            slot.item.getObjectId() + "|" + seller.getIp() + "|" + seller.getPort();

                    broadcast(message);
                    System.out.println("[AuctionServer][finalizeAuction] Winner of auction for object " +
                            slot.item.getObjectId() + " is " + winner.getUsername() + ". Waiting for confirmation.");
                    return;
                } else if (winner == null) {
                    System.out.println("[AuctionServer][finalizeAuction] Winner disconnected, auction cancelled or no winner broadcast");
                } else if (seller == null) {
                    System.out.println("[AuctionServer][finalizeAuction] Seller disconnected, auction invalid");
                }
            }else {
                message = "AUCTION_FINISHED_NO_WINNER" + "|" + slot.item.getObjectId();
                broadcast(message);
            }
                activeAuctions.remove(slot.item.getObjectId());
        }
    }

    public String handleCancellation(String tokenId, String objectId) {
        ActiveAuctionSlot slot = activeAuctions.get(objectId);

        if (slot == null) {
            System.out.println("[AuctionServer][handleCancellation] Cancel failed: No active auction running.");
            return "ERROR|NO_ACTIVE_AUCTION";
        }

        if (slot.item.getState() != AuctionState.PENDING_CONFIRMATION) {
            System.out.println("[AuctionServer][handleCancellation] Request denied: This auction is not accepting trade confirmations.");
            return "ERROR|INVALID_AUCTION_STATE";
        }

        // 2. Validate the objectId safely
        if (objectId == null || !slot.item.getObjectId().equals(objectId)) {
            System.out.println("[AuctionServer][handleCancellation] Cancel failed: Object ID mismatch.");
            return "ERROR|INVALID_OBJ_ID";
        }

        // 3. Find the peer who wants to flake
        ActivePeer flake = activeSessions.get(tokenId);
        if (flake == null) {
            System.out.println("[AuctionServer][handleCancellation] Cancel failed: Flaking peer not found in active sessions.");
            return "ERROR|NOT_FOUND";
        }

        // 4. Ensure they are actually the legitimate winner
        if (!tokenId.equals(slot.currentHighestBidderToken)) {
            System.out.println("[AuctionServer][handleCancellation] Cancel failed: Peer is not the current highest bidder.");
            return "ERROR|WRONG_WINNER";
        }

        System.out.println("[AuctionServer][handleCancellation] Bidder " + flake.getUsername() + " cancelled!! Penalizing...");
        flake.updateReputation(true);
        System.out.println("[AuctionServer][handleCancellation] Bidder " + flake.getUsername() 
                           + " now has a reputation of " + flake.getReputation());
        activeAuctions.remove(objectId);
        return "AUCTION_CANCELLED_SUCCESS";
    }

    public String handleSuccess(String tokenId, String objectId) {
        ActiveAuctionSlot slot = activeAuctions.get(objectId);

        if (slot == null) {
            System.out.println("[AuctionServer][handleSuccess] Auction failed: No active auction running.");
            return "ERROR|NO_ACTIVE_AUCTION";
        }

        if (slot.item.getState() != AuctionState.PENDING_CONFIRMATION) {
            System.out.println("[AuctionServer][handleSuccess] Request denied: This auction is not accepting trade confirmations.");
            return "ERROR|INVALID_AUCTION_STATE";
        }

        // 2. Validate the objectId safely
        if (objectId == null || !slot.item.getObjectId().equals(objectId)) {
            System.out.println("[AuctionServer][handleSuccess] Auction failed: Object ID mismatch.");
            return "ERROR|INVALID_OBJ_ID";
        }

        // 3. Find the peer who wants to flake
        ActivePeer winner = activeSessions.get(tokenId);
        if (winner == null) {
            System.out.println("[AuctionServer][handleSuccess] Auction failed: Winner peer not found in active sessions.");
            return "ERROR|NOT_FOUND";
        }

        // 4. Ensure they are actually the legitimate winner
        if (!tokenId.equals(slot.currentHighestBidderToken)) {
            System.out.println("[AuctionServer][handleSuccess] Auction failed: Peer is not the current highest bidder.");
            return "ERROR|WRONG_WINNER";
        }
        ActivePeer seller = activeSessions.get(slot.item.getSellerTokenId());
        if (seller == null) {
            System.out.println("[AuctionServer][handleSuccess] Seller disconnected, auction invalid");
            activeAuctions.remove(objectId);
            return "AUCTION_CANCELLED_SELLER_UNAVAILABLE";
        }

        System.out.println("[AuctionServer][handleSuccess] Bidder " + winner.getUsername() + " won the item!!");
        winner.updateReputation(false);
        System.out.println("[AuctionServer][handleCancellation] Bidder " + winner.getUsername() 
                           + " now has a reputation of " + winner.getReputation());
        winner.incrementBidderCount();
        seller.incrementSellerCount();

        activeAuctions.remove(objectId);
        return "AUCTION_COMPLETED_SUCCESS";
    }

    // Returns a response string indicating the current auction status.
    public synchronized String getCurrentAuctionResponse() {
        if (activeAuctions.isEmpty()) return "NO_ACTIVE_AUCTION";

        StringBuilder responseBuilder = new StringBuilder("CURRENT_AUCTION");
        for (ActiveAuctionSlot slot : activeAuctions.values()) {
            if (slot.item.getState() == AuctionState.RUNNING) {
                responseBuilder.append("|").append(slot.item.getObjectId()).append("#").append(slot.item.getDescription());
            }
        }
        return responseBuilder.toString();
    }

    // Returns the highest bid and the corresponding bidder token for the current auction.
    public synchronized String getAuctionDetailsResponse(String objectId) {
        ActiveAuctionSlot slot = activeAuctions.get(objectId);
        if (slot == null) return "[ERROR]|No active auction";
        return "AUCTION_DETAILS|" + objectId + "|" + slot.item.getSellerTokenId() + "|" + slot.item.getHighestBid() + "|" + slot.auctionEndTime;
    }

    public synchronized String processBid(String tokenId, String objectId, double amount) {
        //Checks if the bid is the highest bid for the current auction. If yes, it updates the current highest bid and bidder token. Otherwise, it returns an error message.
        ActiveAuctionSlot slot = activeAuctions.get(objectId);
        if (slot == null) return "[ERROR]|No active auction";
        if (amount > slot.item.getHighestBid()) {
            slot.item.setHighestBid(amount);
            slot.currentHighestBidderToken = tokenId;
            System.out.println("[AuctionServer][proccessBid] highestBidderToken = " + slot.currentHighestBidderToken);
            System.out.println("[AuctionServer][proccessBid] highestBid = " + slot.item.getHighestBid());
            return "NEW_BID|OK|" + amount + "|" + slot.item.getObjectId();
        }
        return "NEW_BID|ERROR|" + slot.item.getHighestBid() + "|" + slot.item.getObjectId();
    }

    public String updateOwner(String objectId, String tokenId) { // Γίνεται με την αγορά ενός object από κάποιον bidder
        if (!objectOwnership.containsKey(objectId)) return "[ERROR]|No such object exists";

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

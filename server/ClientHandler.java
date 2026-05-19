package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;

import shared.models.AuctionItem;


public class ClientHandler implements Runnable {
    
    private AuctionServer server;
    private Socket clientSocket;
    

    public ClientHandler(Socket s, AuctionServer server){
        this.clientSocket=s;
        this.server=server;
    }

    public void run(){

        PrintWriter writer = null;

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream()),true)
        ) {
            writer = out; // Το μεταφέρουμε, ώστε να μπορούμε να το αφαιρέσουμε από τη clientWriters λίστα στο finally
            server.addClientWriters(writer); // Προσθέτουμε το κανάλι επικοινωνίας του στη λίστα στον ActionServer

            String message;
            while ((message = in.readLine())!=null){

                handleMessage(message,out); //handle the message of the peer/bidder
                
            }

        } catch (IOException e){
            System.err.println("[ERROR] Lost connection to client.");
        } finally {
            if (writer != null) {
                server.removeClientWriters(writer);
            }
        }
    }
    

    private void handleMessage(String message, PrintWriter out){
        String response="";
        try {
            String[] parts = message.split("\\|");
            
            
            
            switch (parts[0]){
                case "REGISTER" ->  response = handleRegister(parts);
                case "LOGIN" -> response = handleLogin(parts);
                case "REQUEST_AUCTION" -> response = getAuctionRequest(parts);
                case "LOGOUT" -> response = handleLogout(parts);
                case "GET_CURRENT_AUCTION" -> response = server.getCurrentAuctionResponse();
                case "GET_AUCTION_DETAILS" -> response = server.getAuctionDetailsResponse();
                case "PLACE_BID" -> {
                    if (parts.length < 3) throw new IllegalArgumentException("Missing bid data");
                        response = server.processBid(parts[1], Double.parseDouble(parts[2]));
                    }
                case "UPDATE_OWNER" -> response = server.updateOwner(parts[1], parts[2]);
                case "CANCEL_BID" -> response = server.handleCancellation(parts[1],parts[2]);
                case "ACK" -> response = server.handleSuccess(parts[1],parts[2]);
            }
        } catch (IllegalArgumentException e){
            response = e.getMessage();
        } catch (Exception e){
            response = "[ERROR]|Unexpected server failure "+e.getMessage();
        }

        out.println(response);
    }

    

    

    private String handleRegister(String[] parts){
        if (parts.length<3){
            throw new IllegalArgumentException("Invalid command format.");
        }

        String username = parts[1];
        String password = parts[2]; 
        UserProfile newUser = new UserProfile(username, password);
        if (server.registerUser(newUser)) {
            System.out.println("[AUCTION_SERVER] " + username + " registered successfully.");
            return "REGISTER_OK|Welcome to the auction";
        } else {
            return "[ERROR]|Username already taken";
        }

        
    }

    private String handleLogin(String[] parts){

        if (parts.length<3){
            throw new IllegalArgumentException("[ERROR]|Invalid command format, missing data");
        }

        String username = parts[1];
        String password = parts[2];
        UserProfile user = server.getUser(username);
        if (user==null){
            return "[ERROR]|No account found with username " + username;
        } else if (!password.equals(user.getPassword())){
            return "[ERROR]|Incorrect password";
        } else {
            String tokenId = server.generateToken();
            ActivePeer newSession = new ActivePeer(username,
                tokenId,
                user.getSellerCount(),
                user.getBidderCount(),
                user.getReputation()
            );
            if (!server.isLoggedIn(username)){
                server.addActiveSession(newSession);            
                System.out.println("[AUCTION_SERVER] " + username + " logged in successfully.");
                return "LOGIN_OK|"+tokenId;                
            } else {
                return "[ERROR]|Already logged in";
            }
        }

        

    }

    private String getAuctionRequest(String[] parts) {
        //request format: REQUEST_AUCTION|tokenId|ip|port|auctionItem...
        if (parts.length<8){
            throw new IllegalArgumentException("[ERROR]|Invalid command format for auction request");
        }

        String tokenId = parts[1]; 
        ActivePeer active = server.getActivePeer(tokenId); //check if peer is active (logged in)
        if (active==null){
            return "[ERROR]|Required login";
        }

        String ip=parts[2];
        String port = parts[3];
        String objectId=parts[4];
        String desc = parts[5];
        Double startBid=0.0;
        Integer duration =0;
        try {
            startBid=Double.parseDouble(parts[6]);
            duration = Integer.parseInt(parts[7]);
        } catch (NumberFormatException e){
            throw new IllegalArgumentException("[ERROR]|Invalid value for start bid or duration.");
        }

        server.updateConnectionInfo(active, ip, port);
        AuctionItem item = new AuctionItem(tokenId,objectId, desc, startBid, duration);
        server.addToAuctionQueue(item);
        return "AUCTION_REQUEST_OK|Auction request OK";

    }

    private String handleLogout(String[] parts) {
        //request format: LOGOUT|tokenid
        if (parts.length<2){
            throw new IllegalArgumentException("[ERROR}|Invalid command format for LOGOUT, missing token ID");
        }
        String tokenId = parts[1];
        ActivePeer peer = server.getActivePeer(tokenId);
        if (peer==null){
            return "[ERROR]|User is not logged in.";
        }

        server.removePeer(tokenId);
        System.out.println("[AUCTION_SERVER] " + tokenId + " registered successfully.");
        return "LOGOUT_OK|Logged out successfully";
    }
}

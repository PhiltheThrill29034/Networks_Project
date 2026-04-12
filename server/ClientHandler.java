package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Random;


public class ClientHandler implements Runnable {
    
    private AuctionServer server;
    private Socket clientSocket;
    

    public ClientHandler(Socket s, AuctionServer server){
        this.clientSocket=s;
        this.server=server;
    }

    public void run(){

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream()),true)
        ) {
            String message;
            while ((message = in.readLine())!=null){

                handleMessage(message,out);
                
            }

        } catch (IOException e){
            System.err.println("ERROR lost connection to client.");
        }
    }
    

    private void handleMessage(String message, PrintWriter out){
        String response="";
        try {
            String[] parts = message.split("\\|");
            
            
            switch (parts[0]){
                case "REGISTER" ->  response = handleRegister(parts);
                case "LOGIN" -> handleLogin(parts);
                case "REQUEST_AUCTION" -> getAuctionRequest(parts);
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
            return "[REGISTER_OK]|Welcome to the auction";
        } else {
            return "[ERROR]|Username already taken";
        }

        
    }

    private String handleLogin(String[] parts){

        if (parts.length<3){
            throw new IllegalArgumentException("Invalid command format.");
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
                user.getBidderCount()
            );
            if (server.addActiveSession(newSession)){
                return "SUCCESS|"+tokenId;
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
        ActivePeer active = server.getActivePeer(tokenId);
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

    }
}

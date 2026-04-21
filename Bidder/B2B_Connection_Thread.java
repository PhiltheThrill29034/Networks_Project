package Bidder;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.net.Socket;

public class B2B_Connection_Thread extends Thread { // Τα Thread που εξηπυρετούν τα διάφορα αιτήματα
                                                    // που προέρχονται από την σύνδεση των 2 Bidder  
     
    Socket auctionSocket;
    String biddersname;

    B2B_Connection_Thread(Socket auctionSocket, String biddersname) {
        this.auctionSocket = auctionSocket;
        this.biddersname = biddersname;

    }

    @Override
    public void run() {

    try (BufferedReader in = new BufferedReader(new InputStreamReader(auctionSocket.getInputStream()));
         PrintWriter out = new PrintWriter(new OutputStreamWriter(auctionSocket.getOutputStream()), true);
        ){
            String request = in.readLine();
            if (request != null) {
                
                String[] parts = request.split("\\|");

                // BUY_OBJECT|objectId
                if (parts.length != 2) {
                    out.println("[ERROR]|Incorrect request format.");
                    return;
                }

                String command = parts[0].trim();
                String objectId = parts[1].trim();

                if (command.toUpperCase().equals("BUY_OBJECT")) {
                    Path filePath = Paths.get("shared_directory", this.biddersname + "_objects", objectId + ".txt");

                    if (Files.exists(filePath)) {
                        boolean transferred = false;

                        // Ξαναπροσπαθούμε μέχρι να μεταφερθούν τα δεδομένα
                        while (!transferred) {
                            String metadata = Files.readString(filePath);
                            out.println(metadata); // Στέλνουμε τα metadata του object
                            System.out.println("[B2B] " + this.biddersname + " sent " + objectId + "'s metadata.");

                            // Έλεγχος για τη σωστή μεταφορά των δεδομένων
                            String response = in.readLine();
                            if (response.trim().equals("[ERROR]")) {
                                continue;
                            } else if (response.trim().equals("[OK]")) {
                                // Μόνο αν πραγματοποιήθηκε η μεταφορά των δεδομένων διαγράφουμε το object
                                Files.delete(filePath);
                                System.out.println("[B2B] " + objectId + " deleted from " + this.biddersname + " directory.");
                                transferred = true;
                            }
                        }
                    }
                } else {
                    out.println("[ERROR]|Wrong command.");
                    return;
                }      
            } else {
                out.println("[ERROR]|No request sent.");
            }
    } catch (IOException e) {
        e.printStackTrace();
    }

    }

}

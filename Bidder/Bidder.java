package Bidder;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Scanner;

import java.net.Socket;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.Random;


public class Bidder { // Το αρχείο που τρέχουμε για να αρχίσουμε την δημοπρασία με την συμμετοχή των Bidders

    private int b2bServerPort;

    private String biddersName;
    private String biddersPassword;

    private String tokenId;

    private final String AUCTION_SERVER_IP = "localhost";
    private final int AUCTION_SERVER_PORT = 5000; 

    private Socket auctionSocket;
    private BufferedReader in;
    private PrintWriter out;
    private Scanner userInput;

    private String response;

    private Object_Generator objectGenerator;   //
    private B2B_Server_Thread b2bServerThread;        // 
    private ScheduledExecutorService scheduler; //       Threads. Τα αρχικοπιούμε ως πεδία της κλάσης, για να μπορούμε να τα κλείνουμε όταν κάνουμε logout
    private Thread listener;                    //

    Bidder(int port, String name, String password) {
        this.b2bServerPort = port;
        this.biddersName = name;
        this.biddersPassword = password;
        startBidder();
    }

    // Το Thread αυτό λειτουργεί ως ο δίαμεσος στην επικοινωνία ανάμεσα στον Auction server και τον Bidder, 
    // ώστε να μην περνάνε οι λάθος απαντήσεις στον Bidder, όταν βρίσκεται σε αναμονή για απάντηση.
    // Χρησιμοποιείται στο logout, requestAuction, getCurrentAction, getAuctionDetails.
    private class AuctionListenerThread extends Thread {

        @Override
        public void run() {
            try {
                String inputFromServer = in.readLine();

                while (inputFromServer != null) {

                    String[] parts = inputFromServer.split("\\|");
                    String command = parts[0];

                    switch (command) {
                        case "LOGOUT_OK":
                            // Διαχειριζόμαστ την περίπτωση του "[SUCCESS]|Logout..."
                            if (parts[1].contains("Logged out")) {
                                System.out.println("[Bidder] " + biddersName + " logout request confirmed by Auction server");
                                // ?? Μηπως χρειαζεται περαιτερω διαχειριση το logout
                            }
                            break;

                        case "CURRENT_AUCTION":
                            // CURRENT_AUCTION|auctionObjectId|auctionObjectDescription
                            String auctionObjectId = parts[1];
                            String auctionObjectDescription = parts[2];
                            handleCurrentAuction(auctionObjectId, auctionObjectDescription);
                            break;

                        case "AUCTION_DETAILS":
                            // AUCTION_DETAILS|auctionBiddersToken_Id|auctionObjectHighestBid|auctionTimeLeft
                            String auctionBiddersTokenId = parts[1];
                            double auctionObjectHighestBid = Double.parseDouble(parts[2]);
                            int auctionTimeLeft = Integer.parseInt(parts[3]); 
                            handleAuctionDetails(auctionBiddersTokenId, auctionObjectHighestBid, auctionTimeLeft);
                            break;

                        case "NEW_BID":
                            // υλοποίηση placeBid
                            String status = parts[1];
                            double bidAmount = Double.parseDouble(parts[2]);
                            String objId = parts[3];

                            if (status.equals("OK")) {
                                System.out.println("[SUCCESS] Η προσφορά σας των " + bidAmount + "€ για το " + objId + " έγινε δεκτή!");
                                } else {
                                    System.out.println("[REJECTED] Η προσφορά των " + bidAmount + "€ είναι πολύ χαμηλή. Τρέχουσα τιμή: " + parts[4]);
                                }
                            break;

                        case "AUCTION_FINISHED":
                            // ελέγχουμε αν κερδίσαμε εμείς την δημοπρασία, και αν ναι, ξεκινάμε το B2B
                            String winnerToken = parts[1];
                            String objectId = parts[2];
                            
                            if (winnerToken.equals(getTokenId())) {
                                System.out.println("[SUCCESS] I WON the auction for " + objectId + "!");
                                String sellerIp = parts[3];
                                int sellerPort = Integer.parseInt(parts[4]);
                                
                                startTransactionAsBuyer(objectId, sellerIp, sellerPort);
                            }
                            break;

                        case "UPDATED_OWNER":
                            System.out.println("[Bidder]. Ownership was updated successfully.");
                            break;
                        
                        case "[ERROR]":
                            System.err.println("[Bidder] ERROR. Something failed with the data transfered between " 
                                               + biddersName + " Bidder and Auction Server.");
                            break;

                        default:
                            System.err.println("[Bidder] " + biddersName + " received no correct command or ERROR from Auction server.");
                            break;
                    }

                }

            } catch (IOException e) {
                System.err.println("[Bidder] Connection from " + biddersName + " to Auction server closed.");
            }
        }

    }


    public void startBidder() { // Ενεργοποιεί όλη τη διαδικασία

        // Ξεκινάμε το Thread όπου θα χειρίζεται τις συνδέσεις μεταξύ αυτού και των άλλων Bidder
        b2bServerThread = new B2B_Server_Thread(b2bServerPort, getName());
        b2bServerThread.start();

        // Συνδεόμαστε στο Auction Server
        // Επαναλαμβάνεται μέχρι να γίνει το connection 
        while (!connectToAuctionServer()) {
            continue;
        }
        
        // Κάνουμε register
        try {
            // Επαναλαμβάνεται μέχρι να γίνει το register
            while (!register()) {
                continue;
            } 
        } catch (IOException e) {
            System.err.println("ERROR during registration with input to " + this.biddersName + " bidder from Auction server");
        }
        
        // Κάνουμε login
        try {
            // Επαναλαμβάνεται μέχρι να γίνει το login
            while (!login()) {
                continue;
            }

            // Ξεκινάμε την παραγωγή των Object_00.txt αρχείων
            objectGenerator = new Object_Generator(this); // Δεν χρειάζεται κάποιο port, γιατί το Thread απλά θα προσθέτει αρχεία σε φάκελο
            objectGenerator.start();

            // Ξεκινάμε το χρονόμετρο του Auction για τη μεθοδο getCurrentAuction
            startAuctionTimer();

            // Ενεργοποιούμε το AuctionListenerThread, το οποίο επεξεργάζεται αυτό τώρα τις εισόδους από το Auction server 
            if (this.tokenId != null) {
                System.out.println("[Bidder] Starting Auction server listener Thread for " + this.biddersName);
                listener = new Thread(new AuctionListenerThread());
                listener.setDaemon(true); // Αυτό κάνει το συγκεκριμένο Thread να κλείσει από μόνο του, όταν κλείσει το main που την εκτελεί
                listener.start();
            }

        } catch (IOException e) {
            System.err.println("ERROR during login with input to " + this.biddersName + " bidder from Auction server");
        }

        // Το κάνουμε αυτό, για να εξασφαλίσουμε ότι ο Bidder θα κάνει logout όταν κλείσει εντελώς το πρόγραμμα
        logoutWhenProgramShutsDown();
        
    }


    private boolean connectToAuctionServer() {

        try {   
            auctionSocket = new Socket(AUCTION_SERVER_IP, AUCTION_SERVER_PORT);
            in = new BufferedReader(new InputStreamReader(auctionSocket.getInputStream()));
            out = new PrintWriter(auctionSocket.getOutputStream(), true);
            userInput = new Scanner(System.in); 

            System.out.println("[Bidder] Connected to Auction server successfully.");
            return true;

        } catch (IOException e) {
            System.err.println("[Bidder] Failed to connect to Auction server.");
            return false;
        }
    }


    private boolean register() throws IOException {
        boolean registered = false;

        while (!registered) {
            out.println("REGISTER|" + this.biddersName + "|" + this.biddersPassword); // REGISTER|username|password
            response = in.readLine(); // Απάντηση του Auction server
            
            String[] message = null;
            // Επεξεργαζόμαστε την απάντηση από το Auction server
            if (response != null) {
                if (response.contains("|")) { // Υπάρχουν παραπάνω από ένα στοιχεία
                    message = response.split("\\|");
                } else { // Υπάρχει μόνο ένα στοιχείο
                    message = new String[1]; 
                    message[0] = response;
                }
            } else { // Δεν ήρθε απάντηση από τον Auction server
                System.err.println("[Bidder] No response received from Auction server after registration request.");
                continue;
            }
            // Επιβεβαιώνουμε την επιτυχής εγγραφή στο Auction Server
            if (message.length == 2) {
                if (message[0].equals("REGISTER_OK")) {
                    System.out.println("[Bidder] Registered successfully to the Auction server");
                    registered = true;
                } else if (message[0].equals("[ERROR]")) {
                    
                    if (message[1].startsWith("Username")) {
                        System.out.println("[Bidder]" + this.biddersName + " registration failed. Username already taken. Enter new one: ");
                        this.biddersName = userInput.nextLine(); 
                    } else if (message[1].startsWith("Unexpected")) {
                        System.err.println("[Bidder] ERROR during " + this.biddersName + " registration.");
                    }
                }
            } else {
                if (message[0].equals("Invalid command format.")) { // Δόθηκαν λάθος μορφής δεδομένα
                    System.err.println("[Bidder] Invalid command format sent to Auction Server for registration.");
                }
            }
        }
        return registered;
    }

    public boolean login() throws IOException {
        boolean loggedIn = false;

        while (!loggedIn) {
            out.println("LOGIN|" + this.biddersName + "|" + this.biddersPassword); // LOGIN|username|password
            response = in.readLine();
            String[] message = null;
            if (response != null) {
                if (response.contains("|")) {
                    message = response.split("\\|");
                } else {
                    message = new String[1];
                    message[0] = response;
                }
            } else {
                System.err.println("[Bidder] No response received from Auction server after login request.");
                continue;
            }
            if (message.length == 2) {
                if (message[0].equals("LOGIN_OK")) {
                    System.out.println("[Bidder] Logged in successfully to Auction server.");
                    this.tokenId = message[1];
                    loggedIn = true;
                } else if (message[0].equals("[ERROR]")) {
                    if (message[1].startsWith("No account")) {
                        System.err.println("[Bidder] No account found with username " + this.biddersName + ". Please enter your username again: ");
                        this.biddersName = userInput.nextLine();
                    } else if (message[1].equals("Incorrect password")) {
                        System.err.println("[Bidder] Incorrect password for " + this.biddersName + ".Please enter your password again: ");
                        this.biddersPassword = userInput.nextLine();
                        continue;
                    } else if (message[1].startsWith("Already")) {
                        System.out.println("[Bidder] " + this.biddersName + "is already logged in.");
                        break;
                    }
                }
            } else {
                if (message[0].equals("Invalid command format.")) {
                    System.err.println("[Bidder] Invalid command format sent to Auction Server for logging in.");
                }
            }
        }
        return loggedIn;
    }

    public void logout() {
        if (tokenId != null && out != null) {
            // Περνάμε το request στο Auction server (Κάνει logout, ανεξάρτητα από την απάντηση του Auction server)
            out.println("LOGOUT|" + tokenId);
            System.out.println("[Bidder] " + this.biddersName + " sent LOGOUT command to Auction server.");

            try {
                // Κλείνουμε το Thread - Χρονόμετρο
                if (scheduler != null) {
                    scheduler.shutdownNow(); // Κλείνει το Thread
                    System.out.println("[Bidder] " + this.biddersName + "'s auction timer stopped.");
                }

                // Σταματάμε το Thread - Generator
                if (objectGenerator != null) {
                    objectGenerator.interrupt();
                    System.out.println("[Bidder] " + this.biddersName + "'s object generator stopped."); 
                }

                // Κλείνουμε τη σύνδεση με τον Auction server
                if (auctionSocket != null && !auctionSocket.isClosed()) {
                    auctionSocket.close();
                }

            } catch (IOException e) {
                System.err.println("[Bidder] ERROR closing " + this.biddersName+ "'s socket.");
            }
        }
    }


    public void requestAuction(String objectId, String description, double startBid, int auctionDuration) {
        if (this.tokenId != null && out != null) {
            String message = "REQUEST_AUCTION|" + this.tokenId + "|localhost|"  + b2bServerPort + "|" + 
                          objectId + "|" + description + "|" + startBid + "|" + auctionDuration;
            out.println(message);
            System.out.println("[Bidder] " + this.biddersName + " sent new auction item (objectid: " + objectId + ") to Auction server");
        }
    }


    private void startAuctionTimer() {
        // Δημιουργούμε ένα executor, για να προγραμματίζει τη εργασία GET_CURRENT_AUCTION κάθε 60 δευτερόλεπτα
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        scheduler.scheduleAtFixedRate(() -> {   // Η απάντηση θα έρθει στο AuctionListenerThread
                                                if (tokenId != null && out != null) {
                                                    out.println("GET_CURRENT_AUCTION|" + tokenId);
                                                } 
                                            }, 0, 60, TimeUnit.SECONDS);
    }

    private void handleCurrentAuction(String auctionObjectId, String auctionObjectDescription) {
        System.out.println("[Auction] Current object: " + auctionObjectId + " - " + auctionObjectDescription);

        // Ρίχνουμε το κάλπικο νόμισμα (60% πιθανότητα)
        Random rand = new Random();
        double coinToss = rand.nextDouble();
        if (coinToss < 0.6) {
            System.out.println("[Bidder] " + this.biddersName + " is interested for object " + auctionObjectId + ". Requesting details from Auction server...");
            // Λέμε στον Auction server να μας στείλει όλες τις πληροφορίες του συγκεκριμένου object
            out.println("GET_AUCTION_DETAILS|" + this.tokenId);
        } else {
            System.out.println("[Bidder] " + this.biddersName + " is not interested for object " + auctionObjectId + ".");
        }

    }

    //ΕΔΩ ΓΙΝΕΤΑΙ ΤΟ PLACE_BID
    private void handleAuctionDetails(String auctionBiddersTokenId, double auctionObjectHighestBid, int auctionTimeLeft) {
        System.out.println("[Bidder] " + this.biddersName + " received all the details of auction object (Seller: " 
        + auctionBiddersTokenId + ", Highest bid: " + auctionObjectHighestBid + ", Time left: " + auctionTimeLeft + ").");

        //ΕΔΩ ΓΙΝΕΤΑΙ ΤΟ placeBid KAI Ο,ΤΙΔΗΠΟΤΕ ΑΛΛΟ ΧΡΕΙΑΖΕΤΑΙ ΓΙΑ ΤΗΝ B2B ΣΥΝΔΕΣΗ
        double randVal = new Random().nextDouble();
        double myNewBid = auctionObjectHighestBid * (1 + (randVal / 10.0));
    
        myNewBid = Math.round(myNewBid * 100.0) / 100.0;

        System.out.println("[Bidder] " + this.biddersName + " placing bid: " + myNewBid);
    
        out.println("PLACE_BID|" + this.tokenId + "|" + myNewBid);
    }

    private void startTransactionAsBuyer(String objectId, String ip, int port) {
        try (Socket b2bSocket = new Socket(ip, port);
             PrintWriter b2bOut = new PrintWriter(b2bSocket.getOutputStream(), true);
             BufferedReader b2bIn = new BufferedReader(new InputStreamReader(b2bSocket.getInputStream()))) {
            
            b2bOut.println("BUY_OBJECT|" + objectId);
            
            String metadata = b2bIn.readLine();
            if (metadata != null) {
                Path path = Paths.get("shared_directory", this.biddersName + "_objects", objectId + ".txt");
                Files.write(path, metadata.getBytes());
                b2bOut.println("[OK]");
                System.out.println("[B2B] Transaction complete. Saved: " + objectId);
            } else {
                b2bOut.println("[ERROR]");
            }

            // Λέμε στον Auction server να ενημερώσει το ownership του αντικειμένου
            out.println("UPDATE_OWNER|" + objectId + "|" + this.tokenId);
            System.out.println("[Bidder] Informed server for change of ownership of " + objectId);
        } catch (IOException e) {
            System.err.println("[B2B] Transaction failed: " + e.getMessage());
        }
    }


    private void logoutWhenProgramShutsDown() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {System.out.println("\n[Bidder] "+ this.biddersName + " shutting down...");
                                                              logout(); }));
    } 


    public String getName() {
        return this.biddersName;
    }

    public String getTokenId() {
        return this.tokenId;
    }

}

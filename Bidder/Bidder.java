package Bidder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;

import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class Bidder { // Το αρχείο που τρέχουμε για να αρχίσουμε την δημοπρασία με την συμμετοχή των Bidders

    private int b2bServerPort;

    private String biddersName;
    private String biddersPassword;

    private String tokenId;

    private int auctionsSeen;

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
        this.auctionsSeen = 0;
        this.b2bServerPort = port;
        this.biddersName = name;
        this.biddersPassword = password;
    }

    // Το Thread αυτό λειτουργεί ως ο δίαμεσος στην επικοινωνία ανάμεσα στον Auction server και τον Bidder, 
    // ώστε να μην περνάνε οι λάθος απαντήσεις στον Bidder, όταν βρίσκεται σε αναμονή για απάντηση.
    // Χρησιμοποιείται στο logout, requestAuction, getCurrentAction, getAuctionDetails.
    private class AuctionListenerThread extends Thread {

        @Override
        public void run() {
            
            try {
                String inputFromServer;

                while ((inputFromServer = in.readLine()) != null) {

                    String[] parts = inputFromServer.split("\\|");
                    String command = parts[0];

                    switch (command) {
                        case "LOGOUT_OK":
                            System.out.println("[Bidder] " + biddersName + " logout request confirmed by Auction server");
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
                            long auctionTimeLeft = Long.parseLong(parts[3]); 
                            handleAuctionDetails(auctionBiddersTokenId, auctionObjectHighestBid, auctionTimeLeft);
                            break;

                        case "AUCTION_REQUEST_OK":
                            System.out.println("Auction request accepted.");
                            break;

                        case "NEW_BID":
                            // NEW_BID|status|bidAmount|objectId
                            String status = parts[1];
                            double bidAmount = Double.parseDouble(parts[2]);
                            String objId = parts[3];

                            if (status.equals("OK")) {
                                System.out.println("[SUCCESS][" + getBiddersName() + "] Your bid of " + bidAmount + " for object " + objId + " was accepted!");
                            } else if (status.equals("ERROR")) {
                                System.out.println("[REJECTED][" + getBiddersName() + "] Your bid of " + bidAmount + " is too low. Current highest bid is " + parts[2] + ".");
                            }
                            break;

                        case "NO_ACTIVE_AUCTION":
                            System.out.println("[Auction] No active auction.");
                        break;

                        case "AUCTION_FINISHED":
                            // ελέγχουμε αν κερδίσαμε εμείς την δημοπρασία, και αν ναι, ξεκινάμε το B2B
                            String winnerToken = parts[1];
                            String objectId = parts[2];
                            
                            if (winnerToken.equals(getTokenId())) {
                                System.out.println("[SUCCESS] I WON the auction for " + objectId + "!");
                                Random r = new Random();
                                double choice = r.nextDouble();
                                if (choice < 0.3){
                                    System.out.println("["+ getBiddersName() +"] I don't want this shit");
                                    out.println("CANCEL_BID|"+tokenId+"|"+objectId);
                                } else {
                                    out.println("ACK|"+tokenId+"|"+objectId);
                                    String sellerIp = parts[3];
                                    int sellerPort = Integer.parseInt(parts[4]);
                                    startTransactionAsBuyer(objectId, sellerIp, sellerPort);

                                }
                                
                            }

                            // Σταματαμε το πρόγραμμα μετά απο ορισμένα Auction
                            auctionsSeen++;
                            if (auctionsSeen >= 2) {
                                System.out.println("[Bidder] " + getBiddersName() + " is exiting...");
                                logout();
                                return;
                            }
                            break;

                        case "AUCTION_FINISHED_NO_WINNER":
                            break;

                        case "UPDATED_OWNER":
                            System.out.println("[Bidder][" + getBiddersName() + "] Ownership was updated successfully.");
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
        b2bServerThread = new B2B_Server_Thread(b2bServerPort, getBiddersName());
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
                System.err.println("[Bidder][" + this.biddersName + "] No response received from Auction server after registration request.");
                continue;
            }
            // Επιβεβαιώνουμε την επιτυχής εγγραφή στο Auction Server
            if (message.length == 2) {
                if (message[0].equals("REGISTER_OK")) {
                    System.out.println("[Bidder][" + this.biddersName + "] Registered successfully to the Auction server");
                    registered = true;
                } else if (message[0].equals("[ERROR]")) {
                    
                    if (message[1].startsWith("Username")) {
                        System.out.println("[Bidder]" + this.biddersName + " registration failed. Username already taken. Enter new one: ");
                        try {
                            wait();
                        } catch (InterruptedException e) { }
                        this.biddersName = userInput.nextLine();
                        notifyAll(); 
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
                    System.out.println("[Bidder][" + this.biddersName + "] Logged in successfully to Auction server.");
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
        System.out.println("[Auction][" + getBiddersName() + "] Current object: " + auctionObjectId + " - " + auctionObjectDescription);

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

    //Receives the current auction details from the Auction Server. Automatically calculates a new bid based on the required formula. Sends the bid to the Server using the "PLACE_BID" protocol.
    private void handleAuctionDetails(String auctionBiddersTokenId, double auctionObjectHighestBid, long auctionTimeLeft) {
        System.out.println("[Bidder] " + this.biddersName + " received all the details of auction object (Seller: " 
        + auctionBiddersTokenId + ", Highest bid: " + auctionObjectHighestBid + ", Time left: " + auctionTimeLeft + ").");

        double randVal = new Random().nextDouble();
        double myNewBid = auctionObjectHighestBid * (1 + (randVal / 10.0));
    
        myNewBid = Math.round(myNewBid * 100.0) / 100.0;

        System.out.println("[Bidder] " + this.biddersName + " placing bid: " + myNewBid);
    
        out.println("PLACE_BID|" + this.tokenId + "|" + myNewBid);
    }

    private void startTransactionAsBuyer(String objectId, String sellerIp, int sellerPort) {
        System.out.println("\n");

        try (DatagramSocket sellerSocket = new DatagramSocket()){
            // Το random που θα χρησιμοποιήσουμε για την παραγωγή των τυχαίων αριθμών
            Random random = new Random();

            // Στέλνουμε το request για την αγορά του object
            String request = "BUY_OBJECT|" + objectId;
            // Το μετατρέπουμε σε bytes
            byte[] requestBytes = request.getBytes();
            // Βρίσκουμε το Address του socket του seller
            InetAddress sellerAddress = InetAddress.getByName(sellerIp);
            // Δημιουργούμε το πακέτο
            DatagramPacket requestBytesPacket = new DatagramPacket(requestBytes, requestBytes.length, sellerAddress, sellerPort);
            // Στέλνουμε το πακέτο 
            sellerSocket.send(requestBytesPacket);

            // Εδώ θα συσσωρέυσουμε όλα τα εισερχόμενα πακέτα σε σειρά, ουσιαστικά δημιουργόντας το αρχείο σε bytes
            ByteArrayOutputStream fileDataBytesStream = new ByteArrayOutputStream();
            // Η μεταβλητή αυτή δηλώνει το αναμενόμενο sequence number του επόμενου πακέτου που θα δεχθούμαι
            int expectedSequenceNumber = 0;

            // Δέσμευση χώρου για την αποδοχή πακέτου
            byte[] dataAsnwerBuffer = new byte[64];
            // Προετοιμασία πακέτου
            DatagramPacket dataPacket = new DatagramPacket(dataAsnwerBuffer, dataAsnwerBuffer.length);
            
            while (true) {
                // Αναμονή μέχρι να ληφθεί το πακέτο
                sellerSocket.receive(dataPacket);

                // Απορρίψη τα πακέτα με πιθανότητα 20%
                if (random.nextDouble() < 0.20) {
                    System.out.println("[Bidder/startTransactionAdBuyer] " + getBiddersName() + 
                                       " denied incoming packet from seller (20%)");
                    // Συνεχίζουμε με το επόμενο πακέτο
                    continue;
                }

                // Εδώ «τυλίγουμε» (wrap) τα data του ackPacket με το μηχανισμό του ByteBuffer, 
                // (δλδ το ackBuffer είναι 64 bytes μετά το τύλιγμα)
                ByteBuffer dataAnswerBuffer = ByteBuffer.wrap(dataPacket.getData(), 0, dataPacket.getLength());
                // Εδώ υπάρχει ένας μηχανισμός του ByteBuffer που λειτουργεί ως εξής. Όταν καλείται το getInt(),
                // το ByteBuffer κοιτάει που βρίσκεται ο τρέχον εσωτερικός του δείκτης. 
                // Επειδή αυτή είναι η πρώτη ανάγνωση των δεδομένων του, αυτός βρίσκεται στην αρχή (δείκτης = 0). 
                // Επομένως, θα κοιτάξει μόνο στα πρώτα 4 bytes, σύμφωνα με το κανόνα της Java, ο οποίος λέει ότι 
                // κάθε int θα είναι πάντα μόνο 4 bytes. Άρα, δεν θα προκύψει πρόβλημα με την εμπλοκή των άλλων bytes 
                // μετά από αυτά τα 4 πρώτα, τα οποία περιέχουν data του object
                int dataSequenceNumber = dataAnswerBuffer.getInt();

                // Ελέγχουμε αν έχουν σταλθεί όλα τα πακέτα (τερματικό πακέτο έχει sequenceNumber -1)
                if (dataSequenceNumber == -1) {
                    // Στέλνουμε ως τελικό ACK το sequence number του τελευταίου πραγαμτικού πακέτου, όχι -1 
                    // χωρίς simulation, γιατί πρέπει αναγκαστικά να στέλνετε το πακέτο.
                    // Σε περίπτωση που δεν σταλθεί, με το break θα βγούμε από το while και δεν θα ξανασταλθεί το πακέτο 
                    sendACK(sellerSocket, -1, dataPacket.getAddress(), dataPacket.getPort(), random, false);
                    // Στάλθηκαν όλα τα πακέτα, άρα βγαίνουμε από το loop
                    break;
                }   

                // Έτσι εξασφαλίζουμε ότι όλα τα πακέτα έρχονται σε σειρά. Απορρίφθονται αυτά που δεν είναι σε σειρά
                if (dataSequenceNumber == expectedSequenceNumber) {
                    System.out.println("[Bidder/startTransactionAdBuyer] " + getBiddersName() + 
                                       " accepted packet with sequence number " + dataSequenceNumber + " from seller");

                    // Θέτουμε τα υπόλοιπα 60 ή λιγότερα bytes του ByteBuffer λίστα byte[] (remaining())                   
                    byte[] metadataSubPacketBytes = new byte[dataPacket.getLength() - 4];
                    // Όπως αναφέραμε και προηγουμένως, ο εσωτερικός δείκτης του ByteBuffer δείχνει τώρα στο 5 κελι 
                    // (δείκτης = 4), καθώς έχουμε ήδη προσπελάσει τα 4 του int sequence number. Επομένως, με το 
                    // .get(...), αντιστοιχίζουμε τα bytes από τον εσωτερικό δείκτη μέχρι το τέλος του ByteBuffer 
                    // στη μεταβλητή metadataSubPacketBytes
                    dataAnswerBuffer.get(metadataSubPacketBytes);
                    // Μεταφέρουμε τα bytes αυτά στη μεταβλητή με τα συνολικά bytes
                    fileDataBytesStream.write(metadataSubPacketBytes);

                    // Μετακινούμαστε στο επόμενο αναμενόμενο sequence number
                    expectedSequenceNumber++;
                } else {
                    System.out.println("[Bidder/startTransactionAdBuyer] " + getBiddersName() + 
                                       " ignored packet with sequence number out of order. Expected sequence number " 
                                       + expectedSequenceNumber + ". Received " + dataSequenceNumber + " from seller");
                }
                // Στέλνουμε το ACK για το συγκεκριμένο πακέτο. Αν λήφθηκε σωστά το πακέτο με σωστό sequnce number,
                // θα σταλθεί ACK επιβεβαίωσης γι΄ αυτό το πακέτο. Αλλιώς, θα σταλθεί ξανά το ACK για το προηγούμενο
                // πακέτο, πράγμα το οποίο θέλουμε να γίνεται
                int ackToSend = expectedSequenceNumber - 1;
                sendACK(sellerSocket, ackToSend, dataPacket.getAddress(), dataPacket.getPort(), random, true);
            }
            // Φτιάχνουμε το object αρχείο στο shared_directory και το γεμίζουμε με τα metadata του
            Path pathToNewFile = Paths.get("shared_directory", this.biddersName + "_objects", objectId + "_auctioned.txt");
            Files.writeString(pathToNewFile, fileDataBytesStream.toString());
        
            System.out.println("[Bidder/startTransactionAdBuyer] " + getBiddersName() + 
                               "'s object transaction from seller completed. Object file saved to shared_directory");
                               
        } catch (IOException e) {
            System.out.println("[Bidder/startTransactionAdBuyer] Something failed during " + getBiddersName() +
                               "'s connection with the seller. Details: ");
            e.printStackTrace();
        }
        System.out.println("\n");
    }

    private void sendACK(DatagramSocket sellerSocket, int sequenceNumber, InetAddress sellerAddress
                         , int sellerPort, Random rand, boolean simulate) throws IOException {
        double possibilityToDropPacket;

        // Σε περίπτωση που δεν θέλουμε να κάνουμε simulate «χάσιμο» πακέτων, το κάνουμε πάντα να το στέλνει 
        // (όταν sequence number = -1, τερματικό πακέτο)
        if (simulate) {
            possibilityToDropPacket = rand.nextDouble();
        } else {
            possibilityToDropPacket = 0.0;
        }

        // Αποστολή των ACK πακέτων με πιθανότητα 80%
        if (possibilityToDropPacket < 0.80) {
            // Ορίζουμε το μέγεθος του buffer σε 64 bytes
            ByteBuffer ackBuffer = ByteBuffer.allocate(64);
            // Τοποθετούμε το sequenceNumber στο buffer 
            ackBuffer.putInt(sequenceNumber);
            // Πέρνουμε τα bytes του buffer σε byte[] λίστα 
            byte[] ackBytesToSend = ackBuffer.array(); 
            // Προετοιμάζουμε το πακέτο για να στείλουμε το ACK
            DatagramPacket ackPacketToSend = new DatagramPacket(ackBytesToSend, ackBytesToSend.length, sellerAddress, sellerPort);
            // Στέλνουμε το ACK πακέτο
            sellerSocket.send(ackPacketToSend);

            System.out.println("[Bidder/startTransactionAdBuyer] " + getBiddersName() + 
                               " sent ACK for sequence number " + sequenceNumber);
        } else {
            // Το ACK πακέτο «χάνεται»
            System.out.println("[Bidder/startTransactionAdBuyer] " + getBiddersName() + 
                                "'s ACK for sequence number " + sequenceNumber + " was not sent (20%)");
        }
    }


    private void logoutWhenProgramShutsDown() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {System.out.println("\n[Bidder] "+ this.biddersName + " shutting down...");
                                                              logout(); }));
    } 


    public String getBiddersName() {
        return this.biddersName;
    }

    public String getTokenId() {
        return this.tokenId;
    }

}

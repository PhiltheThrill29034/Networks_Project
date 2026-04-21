package Bidder;

import java.util.Random;

import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Object_Generator extends Thread {

    private Bidder bidder;

    Object_Generator(Bidder bidder) {
        this.bidder = bidder;
    }

    // Παράγουμε τα αρχεία Object_00.txt με τα metadata κάθε RAND*120 και τα αποθηκέυουμε στον υποφάκελο κάθε Bidder στο shared_directory    
    @Override
    public void run() {
        
        String mainFolderName = "shared_directory"; 
        String subFolderName = bidder.getBiddersName() + "_objects";
        
        Random rand = new Random();

        int objectCount = 0;

        Path subFolderPath = Paths.get(mainFolderName, subFolderName); // Δημιουργούμε το path προς το καινούργιο subfolder
        boolean folderCreated = false;

        // Δημιουργούμε το φάκελο, και σε περίπτωση που αποτύχει, ξαναπροσπαθούμε
        while (!folderCreated) {
            try {
                if (!Files.exists(subFolderPath)) { // Ελέγχουμε αν υπάρχει ήδη τέτοιος φάκελος στο συγκεκριμένο path
                    Files.createDirectories(subFolderPath);
                    System.out.println("[Object Generator] Created folder: " + subFolderName + " inside folder: "+ mainFolderName + ".");
                }
                folderCreated = true; // Αν έχει φτάσει εδώ το Thread σημαίνει ότι είτε υπάρχει ήδη ο φάκελος, είτε ότι φτίαχτηκε με επιτυχία 

            } catch (IOException e) {
                System.err.println("[Object Generator] Failed to create subfolder for Bidder: " + bidder.getBiddersName() + ".");
                
                // «Κοιμίζουμε» το Thread και ξαναπροσπαθούμε
                try {
                    Thread.sleep(5000); // 5 δευτερόλεπτα
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break; // Οταν το Thread διακόπτεται(interrupted), θα πρέπει να σταματάει και η λειτουργία του
                }
            } 
        }   

        // Παράγουμε τα Objects
        while (true) { // Χρησιμοποιούμε το true γιατί θέλουμε να τρέχει σε όλη τη διάρκεια της ύπαρξης του Bidder   
            try {
                double random = rand.nextDouble();
                long waitTime = (long) (random * 120);

                // Κάνουμε το Thread να σταματάει να παράγει Objects για waitTime(σε δευτερόλεπτα) χρόνο
                Thread.sleep(waitTime * 1000); // Το 1000 μετατρέπει τον χρόνο σε δευτερόλεπτα

                objectCount++;
                createObject(subFolderPath, objectCount, random);

            } catch (InterruptedException ie) {
                System.err.println("[Object Generator] The Object Generator was interrupted.");
                Thread.currentThread().interrupt(); // Γινεται interrupt συνήθως από το κύριο πρόγραμμα
                break;

            } catch (IOException ioe) {
                System.err.println("[Object Generator] Failed to create file in folder. Details: " + ioe.getMessage());
            }

        }
    }

    void createObject(Path subFolderPath, int objectCount, double random) throws IOException {

        String fileName;

        // Φτίαχνουμε το όνομα του αρχείου, ανάλογα με ποιό σε σειρά είναι
        if (objectCount < 10) {
            fileName = "Object_0" + objectCount;
        } else {
            fileName = "Object_" + objectCount;
        }

        Path filePath = subFolderPath.resolve(fileName + ".txt"); // Δημιουργούμε το path προς το καινούργιο αρχείο/object

        String objectId = fileName;
        String description = "Description for " + objectId;
        double startBid = Math.round((10 + (random * 100.0)) * 100.0) / 100.0; // Το random έχει τιμή από το 0.0 εώς το 1.0, οπότε χρειάζεται περισσότερη επεξεργασία
                                                                                // Δείχνουμε πως γίνεται αυτό στο τέλος του αρχείου.
                                                                                // Υπολογίζουμε με αυτό το τρόπο το bid, γιατί θέλουμε να εξασφαλίσουμε ότι θα έχει μόνο 2 δεκαδικά ψηφία, όπως στην πραγματικότητα (67.15€). 
        int auctionDuration = 30 + (int) (random * 60); // Πολλαπλασιάζουμε με 60, και προσθέτουμε 30, 
                                                        // για να περιφερόμαστε στα όρια του 30 και 90 (δευτερόλεπτα). 

        String fileContent = "object_id: " + objectId + "; description: " + description + 
                            "; start_bid: " + startBid + "; auction_duration: " + auctionDuration + ";";

        Files.write(filePath, fileContent.getBytes());

        // Ενημερώνουμε το Bidder και αυτος με τη σειρά του τον Auction server για τη δημιουργια του νέου Object
        bidder.requestAuction(objectId, description, startBid, auctionDuration);
    }

}


/* Υπολογισμός start_bid -> - Έστω ότι το random = 0.6715124
                            - Πολλαπλασιάζουμε με 100 και προσθέτουμε 10, και φέρνουμε τη τιμή στη μορφή 67.15124
                            - Τώρα θέλουμε να κρατήσουμε μόνο τη τιμή 67.15. Οπότε πολλαπλασιάζουμε με 100 και φέρνουμε τη τιμή στη μορφή 6715.124
                            - Μετά, χρησιμοποιούμε το Math.round(), για να στρογγυλοποιήσουμε τη τιμή αυτή, και τη φέρνουμε στη μορφή 6715.0
                            - Τελειώνοντας, διαιρούμε με το 100, και φέρνουμε τη τιμή στην μορφή 67.15, η οποία είναι αποδεκτή ώς τιμή σε €  
*/
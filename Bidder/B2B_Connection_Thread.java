package Bidder;

import java.io.IOException;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.List;
import java.util.ArrayList;

public class B2B_Connection_Thread extends Thread { // Τα Thread που εξηπυρετούν τα διάφορα αιτήματα
                                                    // που προέρχονται από την σύνδεση των 2 Bidder  
     
    private DatagramPacket requestPacket;
    private String biddersName;

    B2B_Connection_Thread(DatagramPacket requestPacket, String biddersName) {
        this.requestPacket = requestPacket;
        this.biddersName = biddersName;

    }

    @Override
    public void run() {

        try {
            // Μετατροπή σε String (BUY_OBJECT|objectId)
            String request = new String(requestPacket.getData(), 0, requestPacket.getLength()).trim();

            // Έλεγχοι
            if (request == null) {
                System.out.println("[B2B Connection Thread] Request sent to " + this.biddersName + " was null");
                return;
            }

            if (request.isEmpty()) {
                System.out.println("[B2B Connection Thread] Request sent to " + this.biddersName + " was empty");
                return;
            }

            String[] parts = request.split("\\|");

            if (parts.length != 2) {
                System.out.println("[B2B Connection Thread] Invalid format for BUY_OBJECT request sent to " + this.biddersName);
                return;
            }

            String command = parts[0].trim();
            String objectId = parts[1].trim();

            if (!command.toUpperCase().equals("BUY_OBJECT")) {
                System.out.println("[B2B Connection Thread] Command sent to " + this.biddersName + " is not \"BUY_OBJECT\"");
                return;
            }

            Path filePath = Paths.get("shared_directory", this.biddersName + "_objects", objectId + ".txt");
            // Έλεγχος ύπαρξης αρχείου στo shared_directory του πωλητή
            if (!Files.exists(filePath)) {
                System.out.println("[B2B Connection Thread] Object " + objectId + " was not found inside " + this.biddersName + " object file directory");
                return;
            }


            // Χρειαζόμαστε τα στοιχεία σύνδεσης του αγοραστή, για να μπορούμε να στείλουμε πίσω τα metadata του object
            InetAddress buyerAddress = requestPacket.getAddress();
            int buyerPort = requestPacket.getPort();

            // Μετατροπή του object file σε byte
            byte[] objectFileBytes = Files.readAllBytes(filePath);

            // «Σπάσιμο» του object byte αρχείοu σε μικρότερα πακέτα των 64 bytes
            List<byte[]> packetsToSend = chopUpFile(objectFileBytes);

            // Αποστολή των πακέτων του object byte αρχείου με το πρωτόκολλο Go-Back-N
            boolean objectSent = performGoBackN(packetsToSend, buyerAddress, buyerPort);

            if (!objectSent) {
                System.out.println("[B2B Connection Thread] " + this.biddersName + " failed to send " + 
                                   objectId + "'s metadata to buyer");
                return;
            }

            // Διαγραφή του object αρχείου από το shared_directory
            Files.delete(filePath);
            System.out.println("[B2B Connection Thread] Object " + objectId + " transferred and deleted from " + 
                               this.biddersName + " shared_directory.");

        } catch (IOException e) {
            System.out.println("[B2B Connection Thread] Something failed during " + this.biddersName + 
                               " Go-Back-N transfer. Details: ");
            e.printStackTrace();
        }
        
    }

    private List<byte[]> chopUpFile(byte[] objectFileBytes) {
        List<byte[]> list = new ArrayList<>();
        // Το sequenceNumber θα αποτελεί το πρώτο στοιχείο του κάθε πακέτου (τα πρώτα 4 bytes). 
        int sequenceNumber = 0;
        // Μας βοηθάει στον χωρισμό του αρχικού byte αρχείου, με σκοπό να μην επαναληφθεί κάποιο δεδομένο
        int offset = 0;
        // Το tempBuffer λειτουργεί σαν Array για bytes
        ByteBuffer tempBuffer;
        

        while (offset < objectFileBytes.length) {
            // Σε περίπτωση που το objectFileBytes.length > 60, θέτουμε το μέγεθος του συγκεκριμένου πακέτου σε 60 bytes, 
            // αλλίως σε όσα bytes μένουν στο objectFileBytes
            int subPacketSize = Math.min(60, objectFileBytes.length - offset);
            // Ορίζουμε το μέγεθος του buffer σε 64 bytes
            tempBuffer = ByteBuffer.allocate(64);
            // Μετατρέπει το sequenceNumber σε bytes, και μετά τοποθετεί στην αρχή του (πρώτα 4 bytes)
            tempBuffer.putInt(sequenceNumber);
            // Τοποθετούμε τα bytes του objectFileBytes, ξεκινώντας από το offset, μέχρι να φτάσουμε το ορισμένο μέγεθος subPacketSize
            tempBuffer.put(objectFileBytes, offset, subPacketSize);
            // Πέρνουμε τα bytes του buffer σε byte[] λίστα, και την τοποθετόυμε στη κύρια list
            list.add(tempBuffer.array());
            // Αυξάνουμε το offset, ώστε να καλύπτει τα ήδη κατοχυρωμένα bytes του objectFileBytes
            offset += subPacketSize;
            // Αυξάνουμε το sequenceNumber κατά 1, καθώς συνεχίζουμε στο επόμενο σε σειρά πακέτο
            sequenceNumber++;
        }

        // Προσθήκη πακέτου στη σειρά, το οποίο δηλώνει το τέλος των πακέτων που περιέχουν δεδομένα
        tempBuffer = ByteBuffer.allocate(64);
        tempBuffer.putInt(-1);
        list.add(tempBuffer.array());

        return list;
    }

    private boolean performGoBackN(List<byte[]> packetsToSend, InetAddress buyerAddress, int buyerPort) {
        
        // Δημιουργία νέου UDP socket. Αυτό γίνεται, καθώς αν χρησιμοποιούσαμε το socket από το οποίο δεχθήκαμε το request,
        // θα είχαμε πολλά προβλήματα. Αρχικά, είναι 1, κοινό για όλες τις συνδέσεις για αγοραπωλησία, και σε αντίθεση με το TCP
        // δεν δημιουργείτε νέα σύνδεση για κάθε αγοραπωλησία. Επομένως, σε περίπτωση που γίνονται 2 αγοραπωλησίες ταυτόχρονα, 
        // οι αποστολές των πακέτων θα μπερδέυοταν μεταξύ τους. Επίσης, θέτουμε στο socket timeout = 2 δευτερόλεπτα.
        // Αν αυτό γινότανε στο κύριο socket, όταν θα περίμενε για τη παραλαβή του request, θα περίεμεν μόνο 2 δευτερόλεπτα,
        // το οποίο δεν θέλουμε, καθώς η σύνδεση πρέπει να έιναι ανοιχτή όσο ο συγκεκριμένος Bidder είναι «ζωντανός»
        try (DatagramSocket buyerSocket = new DatagramSocket(); ) {
            // Θέτουμε όριο αναμονής για απάντηση σε 2 δευτερόλεπτα (2000)
            buyerSocket.setSoTimeout(2000);

            // Window size
            final int WINDOW_SIZE = 3;
            // Αντιπροσωπεύει το sequenceNumber του πρώτου σε σειρά μη επιβεβαιωμένου απεσταλμένου πακέτου. 
            // Αν είναι 0, τότε το δεν έχει επιβεβαιωθεί κανένα. 
            // Αν είναι 1, τότε έχει επιβεβαιωθεί το πακέτο με sequenceNumber 0, και ούτο καθεξής... 
            int firstInLineUnconfirmedReceivedPacket = 0;
            // Αντιπροσωπεύει το επόμενο πακέτο, με sequenceΝumber = nextSequenceNumber, προς αποστολή
            int nextSequenceNumber = 0;
            // Το πλήθος των πακέτων προς αποστολή
            int totalPacketsToSend = packetsToSend.size();

            // Τις χρησιμοποιούμε για την αποφυγή endless loop από συνεχόμενα timeout
            int timeoutRetries = 0;
            final int MAX_TIMEOUT_RETRIES = 5;

            // Δέσμευση χώρου για την αποδοχή πακέτου
            byte[] ackAnswerBuffer = new byte[64];
            // Προετοιμασία πακέτου
            DatagramPacket ackPacket = new DatagramPacket(ackAnswerBuffer, ackAnswerBuffer.length);

            // Όσο το sequenceNumber του πρώτου σε σειρά μη επιβεβαιωμένου πακέτου 
            // είναι εντός του πλήθους των συνολικών πακέτων... 
            while (firstInLineUnconfirmedReceivedPacket < totalPacketsToSend && 
                   firstInLineUnconfirmedReceivedPacket != -1) {

                // Όσο το επόμενο πακέτο προς αποστολή δεν υπερβαίνει το όριο του WINDOW_SIZE 
                // (δλδ όσο τα πακέτα που θα σταλθούν σε αυτή την επανάληψη δεν υπερβαίνουν τα 3)
                // && όσο το επόμενο πακέτο προς αποστολή δεν υπερβαίνει το πλήθος των συνολικών πακέτων προς αποστολή
                while (nextSequenceNumber < firstInLineUnconfirmedReceivedPacket + WINDOW_SIZE 
                       && nextSequenceNumber < totalPacketsToSend) {
                    // Το πακέτο των 64 byte προς αποστολή
                    byte[] subPacket = packetsToSend.get(nextSequenceNumber);
                    // Αποστολή του πακέτου στον buyer
                    DatagramPacket packetToSendToBuyer = new DatagramPacket(subPacket, subPacket.length, buyerAddress, buyerPort);
                    buyerSocket.send(packetToSendToBuyer);

                    System.out.println("[B2B Connection Thread/performGoBackN] " + this.biddersName + 
                                       " sent packet with sequence number " + nextSequenceNumber + " to buyer");
                    // Προχωράμε στο επόμενο πακέτο
                    nextSequenceNumber++;
                }

                // Διαχείριση ACK
                try {
                    // Αναμονή μέχρι την παραλαβή του ACK από τον buyer
                    buyerSocket.receive(ackPacket);

                    // Εφόσον ήρθα σε αυτό το κομμάτι του κώδικα, σημαίνει ότι λάβαμε πακέτο. Άρα, μηδενίζουμε το 
                    // μετρητή
                    timeoutRetries = 0;

                    // Εδώ «τυλίγουμε» (wrap) τα data του ackPacket με το μηχανισμό του ByteBuffer, 
                    // (δλδ το ackBuffer είναι 4 bytes μετά το τύλιγμα) ώστε να μπορέσουμε να εξάγουμε
                    // τον 4 byte αριθμό σε int
                    ByteBuffer ackBuffer = ByteBuffer.wrap(ackPacket.getData(), 0, ackPacket.getLength());
                    int ackNumber = ackBuffer.getInt();

                    System.out.println("[B2B Connection Thread/performGoBackN] " + this.biddersName + 
                                       " received ACK for sequence number " + ackNumber + " from buyer");

                    // Ελέγχουμε αν στάλθηκε το τερματικό πακέτο με sequence number -1. Αν ναι, θέτουμε το
                    // firstInLineUnconfirmedReceivedPacket με -1, έτσι ώστε να βγούμε από το εξωτερικό loop
                    if (ackNumber == -1 && firstInLineUnconfirmedReceivedPacket == totalPacketsToSend - 1) {
                        firstInLineUnconfirmedReceivedPacket = -1;
                    // Αν το ACK είναι μεγαλύτερο από το sequenceNumber του πρώτου σε σειρά μη επιβεβαιωμένου πακέτου
                    // θέτουμε αυτό το sequenceNumber να δείχνει στο επόμενο πακέτο από αυτό που δείχνει το ACK.
                    // Αν δεν είναι σημαίνει ότι δεν επιβεβαιώθηκε το πακέτο, και έτσι γυρνάμε πάλι στη διαχείριση
                    // του ACK, μέχρι να ληφθεί το επιθυμιτό ACK
                    } else if (ackNumber >= firstInLineUnconfirmedReceivedPacket) {
                        firstInLineUnconfirmedReceivedPacket = ackNumber + 1;
                    }

                } catch (SocketTimeoutException e) {
                    // Αυξάνουμε το μετρητή των συνεχόμενων timeout και ελέγχουμε για την υπέρβαση του ορίου. 
                    // Αν έχει γίνει η υπέρβαση, τερματίζουμε την επικοινωνία
                    timeoutRetries++;
                    if (timeoutRetries > MAX_TIMEOUT_RETRIES) {
                        System.out.println("[B2B Connection Thread/performGoBackN] Max continuous timeout reached" +
                                           " for " + this.biddersName + ". Buyer is down or final ACK was lost." + 
                                           " Exiting...");
                        return false;
                    }

                    // Δεν λάβαμε κάποιο ACK από τον buyer μέσα στο χρονικό όριο των 2 δευτερολέπτων
                    System.out.println("[B2B Connection Thread/performGoBackN] Timeout! Resending " + this.biddersName + 
                                       " packets from sequence number " + firstInLineUnconfirmedReceivedPacket);
                    // Θέτουμε το επόμενο πακέτο προς αποστολή να είναι το πρώτο από αυτά που δεν έχουν επιβεβαιωθεί,
                    // ώστε να ξανασταλθούν
                    nextSequenceNumber = firstInLineUnconfirmedReceivedPacket;
                }

            }
            // Κλείνουμε το socket
            buyerSocket.close();

            return true;

        } catch (IOException e) {
            System.out.println("[B2B Connection Thread] Something failed during " + this.biddersName + 
                               "'s connection with the buyer. Details: ");
            e.printStackTrace();
            return false;
        }
    }   

}

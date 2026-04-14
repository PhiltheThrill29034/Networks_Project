package Bidder_Gerasimos;

public class Bidder { // Το αρχείο που τρέχουμε για να αρχίσουμε την δημοπρασία με την συμμετοχή των Bidders

    private int b2bServerPort;
    private String biddersName;

    Bidder(int port, String name) {
        this.b2bServerPort = port;
        this.biddersName = name;
        startBidder();
    }

    public void startBidder() { // Ενεργοποιεί όλη τη διαδικασία

        // Ξεκινάμε το Thread όπου θα χειρίζεται τις συνδέσεις μεταξύ αυτού και των άλλων Bidder
        B2B_Server_Thread b2bServerThread = new B2B_Server_Thread(b2bServerPort);
        b2bServerThread.start();

        // Ξεκινάμε την παραγωγεί των Object_00.txt αρχείων
        Object_Generator objectGenerator = new Object_Generator(biddersName); // Δεν χρειάζεται κάποιο port, γιατί το Thread απλά θα προσθέτει αρχεία σε φάκελο
        objectGenerator.start();

        // Register...

        // Login...

    }

}

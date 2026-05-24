package Auction;

public class ActivePeer {
    private String username;
    private String port;
    private String ip;
    private String tokenId;
    private int numAuctionsSeller;
    private int numAuctionsBidder;
    private double reputation_score;

    public ActivePeer(String username, String tokenId,int numAuctionsSeller,int numAuctionsBidder, double reputation_score){
        this.username=username;
        this.tokenId = tokenId;
        this.numAuctionsSeller=numAuctionsBidder;
        this.numAuctionsBidder=numAuctionsBidder;
        this.reputation_score = reputation_score;
    }

    public void setConnectionInfo (String ip, String port){
        this.ip=ip;
        this.port=port;
    }

    public String getUsername() {
        return this.username;
    }

    public String getPort() {
        return this.port;
    }

    public String getIp() {
        return this.ip;
    }

    public String getTokenId() {
        return this.tokenId;
    }

    public int getNumAuctionsSeller() {
        return this.numAuctionsSeller;
    }

    public int getNumAuctionsBidder() {
        return this.numAuctionsBidder;
    }

    public synchronized void incrementSellerCount() {
        this.numAuctionsSeller++;
    }

    public synchronized void incrementBidderCount() {
        this.numAuctionsBidder++;
    }

    public double getReputation(){
        return this.reputation_score;
    }

    public synchronized void updateReputation(boolean cancelled){
        if (cancelled){
            this.reputation_score = 0.75*reputation_score;
        } else {
            this.reputation_score = 0.75*reputation_score + 0.75;
        }
    }
}

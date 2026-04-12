package server;

public class ActivePeer {
    private String username;
    private String port;
    private String ip;
    private String tokenId;
    private int numAuctionsSeller;
    private int numAuctionsBidder;

    public ActivePeer(String username, String tokenId,int numAuctionsSeller,int numAuctionsBidder){
        this.username=username;
        this.tokenId = tokenId;
        this.numAuctionsSeller=numAuctionsBidder;
        this.numAuctionsBidder=numAuctionsBidder;
    }

    public void setConnectionInfo (String ip, String port){
        this.ip=ip;
        this.port=port;
    }

    public String getUsername() {
        return username;
    }

    public String getPort() {
        return port;
    }

    public String getIp() {
        return ip;
    }

    public String getTokenId() {
        return tokenId;
    }

    public int getNumAuctionsSeller() {
        return numAuctionsSeller;
    }

    public int getNumAuctionsBidder() {
        return numAuctionsBidder;
    }

    public synchronized void incrementSellerCount() {
        this.numAuctionsBidder++;
    }

    public synchronized void incrementBidderCount() {
        this.numAuctionsBidder++;
    }
}

package server;

import shared.models.AuctionItem;
import shared.models.AuctionState;

public class ActiveAuctionSlot {
    public AuctionItem item;
    public long auctionEndTime;
    public String currentHighestBidderToken;

    public ActiveAuctionSlot(AuctionItem item) {
        this.item = item;
        this.auctionEndTime = System.currentTimeMillis() + (item.getDuration() * 1000L);
        this.currentHighestBidderToken = null;
    }
}
package net.malkowscy.fuzzytextface;

import android.content.Intent;

import pl.tajchert.servicewear.WearServiceReceiver;

public class DisconnectListenerService extends WearServiceReceiver {

    public static String X = "ddd";

    @Override
    public void onPeerConnected(String peerName, String peerId) {
        Intent ssIntent = new Intent(mContext, FuzzyWatchFace.class);
        ssIntent.setAction("peer-connected");
        mContext.startService(ssIntent);
    }

    @Override
    public void onPeerDisconnected(String peerName, String peerId) {
        Intent ssIntent = new Intent(mContext, FuzzyWatchFace.class);
        ssIntent.setAction("peer-disconnected");
        mContext.startService(ssIntent);
    }
}
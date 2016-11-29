package org.appspot.apprtc;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;

public class FirebaseRTCClient implements AppRTCClient, ValueEventListener {
    private static final String TAG = "FirebaseRTCClient";
    DatabaseReference database;
    SignalingEvents events;
    String myId;

    private boolean is_initiator = false;

    ValueEventListener sdpeventsListener = null;
    ValueEventListener icecandidateListener = null;

    private static final int TURN_HTTP_TIMEOUT_MS = 5000;

    private final Handler handler;

    private Hashtable<String, Boolean> sdpAdded = new Hashtable<String, Boolean>();



    @Override
    public void onDataChange(DataSnapshot dataSnapshot) {
        Log.d(TAG, "onDataChanged");
        if(!dataSnapshot.exists() && !is_initiator) {
            is_initiator = true;
        }

        if(!dataSnapshot.hasChild(myId)) {
            database.child("/channels/firebase").child(myId).child("connected").setValue(true);
            //Connected
            handler.post(new Runnable() {
                @Override
                public void run() {
                    List<PeerConnection.IceServer> iceServerList = null;
                    try {
                        iceServerList = requestTurnServers("https://networktraversal.googleapis.com/v1alpha/iceconfig?key=AIzaSyAJdh2HkajseEIltlZ3SIXO02Tze9sO3NY");
                        //iceServerList = new LinkedList<PeerConnection.IceServer>();
                        SignalingParameters parameters = new SignalingParameters(
                                // Ice servers are not needed for direct connections.
                                iceServerList,
                                is_initiator, // Server side acts as the initiator on direct connections.
                                null, // clientId
                                null, // wssUrl
                                null, // wwsPostUrl
                                null, // offerSdp
                                null // iceCandidates
                        );
                        events.onConnectedToRoom(parameters);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

        }

        if(dataSnapshot.hasChildren()) {
            Iterator<DataSnapshot> children = dataSnapshot.getChildren().iterator();

            while(children.hasNext()) {
                DataSnapshot child = children.next();

                if(child.getKey() != myId) {
                    if(child.hasChild("sdp") ) {
                        child.getChildren();
                        final SessionDescription sdp = getSdp(child.child("sdp"));
                        Log.d(TAG, "onRemoteDescription: " + sdp.description);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if(sdpAdded.get(sdp.type + sdp.description) == null) {
                                    events.onRemoteDescription(sdp);
                                    sdpAdded.put(sdp.type + sdp.description, true);
                                }
                            }
                        });

                    }

                    Iterator<DataSnapshot>  iceList = child.child("icecandidate").getChildren().iterator();

                    while (iceList.hasNext()) {
                        DataSnapshot iceChild = iceList.next();
                        final IceCandidate candidate = getIceCandidate(iceChild);

                        Log.d(TAG, "onRemoteIceCandidate");
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                events.onRemoteIceCandidate(candidate);
                            }
                        });
                    }
                }
            }
        }
    }

    @Override
    public void onCancelled(DatabaseError databaseError) {

    }


    public FirebaseRTCClient(SignalingEvents events) {
        database = FirebaseDatabase.getInstance().getReference();
        this.events = events;

        final HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public void connectToRoom(RoomConnectionParameters connectionParameters) {
        Log.d(TAG, "connect to room : " + connectionParameters.roomId);
        if (connectionParameters.loopback) {
            Log.d(TAG, "Loopback connections aren't supported by FirebaseRTCClient.");
        }
        myId = Build.SERIAL;
        database.child("/channels/firebase").addValueEventListener(this);
    }

    private SessionDescription getSdp(DataSnapshot db) {
        String type = db.child("type").getValue().toString();
        String desc = db.child("description").getValue().toString();

        return new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), desc);
    }

    @Override
    public void sendOfferSdp(SessionDescription sdp) {
        Log.d(TAG, "send offer sdp");
        database.child("/channels/firebase").child(myId).child("sdp").setValue(sdp);
    }

    @Override
    public void sendAnswerSdp(SessionDescription sdp) {
        Log.d(TAG, "send answer sdp");
        database.child("/channels/firebase").child(myId).child("sdp").setValue(sdp);
    }

    private IceCandidate getIceCandidate(DataSnapshot db) {
        String sdp = db.child("sdp").getValue(String.class);
        int sdpMLineIndex = db.child("sdpMLineIndex").getValue(Integer.class);
        String sdpMid = db.child("sdpMid").getValue(String.class);

        return new IceCandidate(sdpMid, sdpMLineIndex, sdp);
    }

    @Override
    public void sendLocalIceCandidate(IceCandidate candidate) {
        Log.d(TAG, "send local ice candidate: " + candidate);
        database.child("/channels/firebase").child(myId).child("icecandidate").child("" + candidate.hashCode()).setValue(candidate);
    }

    @Override
    public void sendLocalIceCandidateRemovals(IceCandidate[] candidates) {
        Log.d(TAG, "send local ice candidate removal");

        for(IceCandidate candidate:candidates) {
            database.child("/channels/firebase").child(myId).child("icecandidate").child("" + candidate.hashCode()).removeValue();
        }
    }

    @Override
    public void disconnectFromRoom() {
        Log.d(TAG, "disconnect from room");
        database.child("/channels/firebase").child(myId).removeValue();
        //database.child("/channels/firebase").child("disconnect").setValue(true);
        database.child("/channels/firebase").removeEventListener(this);
        sdpAdded.clear();
    }

    private LinkedList<PeerConnection.IceServer> requestTurnServers(String url)
            throws IOException, JSONException {
        LinkedList<PeerConnection.IceServer> turnServers = new LinkedList<PeerConnection.IceServer>();
        Log.d(TAG, "Request TURN from: " + url);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setDoOutput(true);
        connection.setRequestProperty("REFERER", "https://appr.tc");
        connection.setConnectTimeout(TURN_HTTP_TIMEOUT_MS);
        connection.setReadTimeout(TURN_HTTP_TIMEOUT_MS);
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Non-200 response when requesting TURN server from " + url + " : "
                    + connection.getHeaderField(null));
        }
        InputStream responseStream = connection.getInputStream();
        String response = drainStream(responseStream);
        connection.disconnect();
        Log.d(TAG, "TURN response: " + response);
        JSONObject responseJSON = new JSONObject(response);
        JSONArray iceServers = responseJSON.getJSONArray("iceServers");
        for (int i = 0; i < iceServers.length(); ++i) {
            JSONObject server = iceServers.getJSONObject(i);
            JSONArray turnUrls = server.getJSONArray("urls");
            String username = server.has("username") ? server.getString("username") : "";
            String credential = server.has("credential") ? server.getString("credential") : "";
            for (int j = 0; j < turnUrls.length(); j++) {
                String turnUrl = turnUrls.getString(j);
                turnServers.add(new PeerConnection.IceServer(turnUrl, username, credential));
            }
        }
        return turnServers;
    }

    // Return the contents of an InputStream as a String.
    private static String drainStream(InputStream in) {
        Scanner s = new Scanner(in).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
}

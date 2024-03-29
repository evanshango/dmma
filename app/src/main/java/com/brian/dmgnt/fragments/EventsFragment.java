package com.brian.dmgnt.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.brian.dmgnt.R;
import com.brian.dmgnt.models.Incident;
import com.brian.dmgnt.models.PolyLineData;
import com.brian.dmgnt.models.UserLocation;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;
import com.google.maps.DirectionsApiRequest;
import com.google.maps.GeoApiContext;
import com.google.maps.PendingResult;
import com.google.maps.internal.PolylineEncoding;
import com.google.maps.model.DirectionsResult;
import com.google.maps.model.DirectionsRoute;

import java.util.ArrayList;
import java.util.List;

import static com.brian.dmgnt.helpers.Constants.API_KEY;
import static com.brian.dmgnt.helpers.Constants.INCIDENTS;
import static com.brian.dmgnt.helpers.Constants.MAP_VIEW_BUNDLE_KEY;
import static com.brian.dmgnt.helpers.Constants.USER_LOCATION;

public class EventsFragment extends Fragment implements OnMapReadyCallback,
        GoogleMap.OnInfoWindowClickListener,
        GoogleMap.OnPolylineClickListener {

    private static final String TAG = "EventsFragment";
    private GoogleMap mGoogleMap;
    private MapView mMapView;
    private CollectionReference incidentsCollection, locationsCollection;
    private List<Incident> mIncidents = new ArrayList<>();
    private double bottomBoundary, topBoundary, leftBoundary, rightBoundary;
    private ImageView btnResetMap;
    private GeoApiContext mGeoApiContext;
    private GeoPoint mGeoPoint;
    private ArrayList<PolyLineData> mPolyLineData = new ArrayList<>();
    private Marker mSelectedMarker = null;
    private ArrayList<Marker> mTripMarkers = new ArrayList<>();
    private ProgressBar eventsLoader;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_events, container, false);

        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();

        FirebaseFirestore database = FirebaseFirestore.getInstance();
        locationsCollection = database.collection(USER_LOCATION);
        incidentsCollection = database.collection(INCIDENTS);

        initViews(view);

        if (user != null) {
            String userId = user.getUid();
            getUserLocation(userId);
        } else {
            Log.d(TAG, "onCreateView: User not logged in");
        }

        return view;
    }

    private void getUserLocation(String userId) {
        eventsLoader.setVisibility(View.VISIBLE);
        locationsCollection.document(userId).get().addOnSuccessListener(documentSnapshot -> {
            UserLocation userLocation = documentSnapshot.toObject(UserLocation.class);
            setMapBounds(userLocation);
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        btnResetMap.setOnClickListener(v -> getIncidents(mGoogleMap));
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        initGoogleMap(savedInstanceState);
    }

    private void setMapBounds(UserLocation userLocation) {
        if (userLocation != null) {
            mGeoPoint = userLocation.getGeoPoint();
            bottomBoundary = userLocation.getGeoPoint().getLatitude() - .1;
            leftBoundary = userLocation.getGeoPoint().getLongitude() - .1;
            topBoundary = userLocation.getGeoPoint().getLatitude() + .1;
            rightBoundary = userLocation.getGeoPoint().getLongitude() + .1;
        } else {
            Log.d(TAG, "setMapBounds: Unable to fetch user location");
        }
    }

    private void getIncidents(GoogleMap googleMap) {
        incidentsCollection.get().addOnSuccessListener(queryDocumentSnapshots -> {
            if (queryDocumentSnapshots != null) {
                mIncidents.addAll(queryDocumentSnapshots.toObjects(Incident.class));
                setMarkers(mIncidents, googleMap);
            } else {
                Log.d(TAG, "getIncidents: No incidents");
            }
        }).addOnFailureListener(e -> Log.d(TAG, "getIncidents: An error occurred"));
    }

    private void initGoogleMap(Bundle savedInstanceState) {
        Bundle mapViewBundle = null;
        if (savedInstanceState != null) {
            mapViewBundle = savedInstanceState.getBundle(MAP_VIEW_BUNDLE_KEY);
        }
        mMapView.onCreate(mapViewBundle);
        mMapView.getMapAsync(this);

        if (mGeoApiContext == null) {
            mGeoApiContext = new GeoApiContext.Builder().apiKey(API_KEY).build();
        }
    }

    private void addPolyLinesToMap(DirectionsResult result) {
        new Handler(Looper.getMainLooper()).post(() -> {

            if (mPolyLineData.size() > 0) {
                for (PolyLineData polyLineData : mPolyLineData) {
                    polyLineData.getPolyline().remove();
                }
                mPolyLineData.clear();
                mPolyLineData = new ArrayList<>();
            }

            double duration = 99999999;
            for (DirectionsRoute route : result.routes) {
                List<com.google.maps.model.LatLng> decodedPath = PolylineEncoding.decode(
                        route.overviewPolyline.getEncodedPath());
                List<LatLng> newDecodedPath = new ArrayList<>();
                for (com.google.maps.model.LatLng latLng : decodedPath) {
                    newDecodedPath.add(new LatLng(latLng.lat, latLng.lng));
                }
                Polyline polyline = mGoogleMap.addPolyline(new PolylineOptions().addAll(newDecodedPath));
                polyline.setColor(ContextCompat.getColor(requireActivity(), R.color.darkGrey));
                polyline.setClickable(true);
                mPolyLineData.add(new PolyLineData(polyline, route.legs[0]));

                double tempDuration = route.legs[0].duration.inSeconds;
                if (tempDuration < duration) {
                    duration = tempDuration;
                    onPolylineClick(polyline);
                    zoomRoute(polyline.getPoints());
                }
                mSelectedMarker.setVisible(false);
            }
        });
    }

    private void calculateDirections(Marker marker) {
        com.google.maps.model.LatLng dest = new com.google.maps.model
                .LatLng(marker.getPosition().latitude, marker.getPosition().longitude);
        DirectionsApiRequest directions = new DirectionsApiRequest(mGeoApiContext);
        directions.alternatives(true);
        directions.origin(
                new com.google.maps.model.LatLng(
                        mGeoPoint.getLatitude(), mGeoPoint.getLongitude())
        );
        directions.destination(dest).setCallback(new PendingResult.Callback<DirectionsResult>() {
            @Override
            public void onResult(DirectionsResult result) {
                Log.d(TAG, "calculateDirections: routes: " + result.routes[0].toString());
                Log.d(TAG, "calculateDirections: geoCodedWayPoints: " + result.geocodedWaypoints[0].toString());
                addPolyLinesToMap(result);
            }

            @Override
            public void onFailure(Throwable e) {
                Log.d(TAG, "onFailure: error " + e.getMessage());
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Bundle mapViewBundle = outState.getBundle(MAP_VIEW_BUNDLE_KEY);
        if (mapViewBundle == null) {
            mapViewBundle = new Bundle();
            outState.putBundle(MAP_VIEW_BUNDLE_KEY, mapViewBundle);
        }
        try {
            mMapView.onSaveInstanceState(mapViewBundle);
        } catch (NullPointerException ex) {
            Log.d(TAG, "onSaveInstanceState: " + ex);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mMapView.onResume();
    }

    @Override
    public void onStart() {
        super.onStart();
        mMapView.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        mMapView.onStart();
    }

    private void initViews(View view) {
        mMapView = view.findViewById(R.id.map);
        btnResetMap = view.findViewById(R.id.btnResetMap);
        eventsLoader = view.findViewById(R.id.eventsLoader);
    }

    @Override
    public void onPause() {
        mMapView.onPause();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mMapView.onDestroy();
        super.onDestroy();
    }

    private void setCameraView() {
        LatLngBounds mapBounds = new LatLngBounds(new LatLng(bottomBoundary, leftBoundary),
                new LatLng(topBoundary, rightBoundary));
        mGoogleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(mapBounds, 0));
    }

    private void resetMap() {
        if (mGoogleMap != null) {
            mGoogleMap.clear();
            if (mPolyLineData.size() > 0) {
                mPolyLineData.clear();
                mPolyLineData = new ArrayList<>();
            }
        }
    }

    private void setMarkers(List<Incident> incidents, GoogleMap googleMap) {
        eventsLoader.setVisibility(View.GONE);
        resetMap();
        for (Incident incident : incidents) {
            LatLng latLng = new LatLng(
                    incident.getGeoPoint().getLatitude(), incident.getGeoPoint().getLongitude()
            );
            googleMap.addMarker(new MarkerOptions().position(latLng).title(incident.getCategory())
                    .snippet(incident.getDescription()));
        }
        setCameraView();
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mGoogleMap = googleMap;
        mGoogleMap.setMyLocationEnabled(true);
        getIncidents(mGoogleMap);
        mGoogleMap.setOnPolylineClickListener(this);
        mGoogleMap.setOnInfoWindowClickListener(this);
    }

    @Override
    public void onInfoWindowClick(Marker marker) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        builder.setTitle(marker.getTitle() + " incident reported.")
                .setMessage(marker.getSnippet() + "\n\nDetermine route to this place?")
                .setCancelable(true)
                .setPositiveButton("Yes", ((dialog, which) -> {
                    resetSelectedMarker();
                    mSelectedMarker = marker;
                    calculateDirections(marker);
                    dialog.dismiss();
                })).setNegativeButton("No", ((dialog, which) -> dialog.cancel()));
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void removeTripMarkers() {
        for (Marker marker : mTripMarkers) {
            marker.remove();
        }
    }

    private void resetSelectedMarker() {
        if (mSelectedMarker != null) {
            mSelectedMarker.setVisible(true);
            mSelectedMarker = null;
            removeTripMarkers();
        }
    }

    private void zoomRoute(List<LatLng> latLngList) {
        if (mGoogleMap == null || latLngList == null || latLngList.isEmpty()) return;
        LatLngBounds.Builder boundBuilder = new LatLngBounds.Builder();
        for (LatLng latLng : latLngList) {
            boundBuilder.include(latLng);
        }
        int routePadding = 120;
        LatLngBounds latLngBounds = boundBuilder.build();
        mGoogleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, routePadding),
                600, null);
    }

    @Override
    public void onPolylineClick(Polyline polyline) {
        int index = 0;
        for (PolyLineData polyLineData : mPolyLineData) {
            index++;
            Log.d(TAG, "onPolylineClick: " + polyLineData.toString());
            if (polyline.getId().equals(polyLineData.getPolyline().getId())) {
                polyLineData.getPolyline().setColor(ContextCompat.getColor(
                        requireActivity(), R.color.blueOne));
                polyLineData.getPolyline().setZIndex(1);

                LatLng endLocation = new LatLng(polyLineData.getDirectionsLeg().endLocation.lat,
                        polyLineData.getDirectionsLeg().endLocation.lng);

                Marker marker = mGoogleMap.addMarker(new MarkerOptions().position(endLocation)
                        .title("Trip: #" + index)
                        .snippet("Duration: " + polyLineData.getDirectionsLeg().duration)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                marker.showInfoWindow();
                mTripMarkers.add(marker);
            } else {
                polyLineData.getPolyline().setColor(ContextCompat.getColor(
                        requireActivity(), R.color.darkGrey));
                polyLineData.getPolyline().setZIndex(0);
            }
        }
    }
}
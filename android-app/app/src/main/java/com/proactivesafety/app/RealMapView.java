package com.proactivesafety.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.Locale;

public class RealMapView extends WebView {
    private boolean loaded = false;
    private Location pendingLocation;
    private SafetyAssessment pendingAssessment;
    private CooperativeAlert pendingAlert;
    private boolean pendingMonitoring;

    public RealMapView(Context context) {
        super(context);
        configure();
        loadDataWithBaseURL("https://proactive-safety.local/", html(), "text/html", "UTF-8", null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure() {
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        setBackgroundColor(0xff07130f);
        setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                if (pendingLocation != null || pendingAssessment != null) {
                    update(pendingLocation, pendingAssessment, pendingAlert, pendingMonitoring);
                }
            }
        });
    }

    public void update(Location location, SafetyAssessment assessment, CooperativeAlert alert, boolean monitoring) {
        pendingLocation = location;
        pendingAssessment = assessment;
        pendingAlert = alert;
        pendingMonitoring = monitoring;
        if (!loaded) return;

        try {
            JSONObject json = new JSONObject();
            json.put("monitoring", monitoring);
            json.put("level", assessment == null ? "LOW" : assessment.level);
            json.put("score", assessment == null ? 0 : assessment.score);

            if (location != null) {
                json.put("lat", location.getLatitude());
                json.put("lng", location.getLongitude());
                json.put("speedKmh", RiskEngine.speedKmh(location));
                json.put("heading", location.hasBearing() ? location.getBearing() : 0);
                json.put("hasLocation", true);
            } else {
                json.put("hasLocation", false);
            }

            if (assessment != null && assessment.nearestZone != null) {
                json.put("zoneName", assessment.nearestZone.name);
                json.put("zoneLat", assessment.nearestZone.latitude);
                json.put("zoneLng", assessment.nearestZone.longitude);
                json.put("hasZone", true);
            } else {
                json.put("hasZone", false);
            }

            if (alert != null && alert.present && alert.hasOtherLocation) {
                json.put("hasOther", true);
                json.put("otherLat", alert.otherLatitude);
                json.put("otherLng", alert.otherLongitude);
                json.put("otherLabel", String.format(Locale.US, "%s %d km/h", alert.direction, alert.closingSpeedKmh));
            } else {
                json.put("hasOther", false);
            }

            evaluateJavascript("window.updateSafetyMap(" + json + ")", null);
        } catch (Exception ignored) {
        }
    }

    private String html() {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'>" +
                "<style>" +
                "html,body,#map{height:100%;margin:0;background:#07130f;font-family:Arial,sans-serif;}" +
                ".leaflet-control-attribution{display:none}" +
                ".hud{position:absolute;left:12px;right:12px;top:12px;z-index:999;background:rgba(0,0,0,.62);border:1px solid rgba(255,255,255,.14);border-radius:10px;padding:10px 12px;color:white}" +
                ".level{font-weight:900;font-size:24px;color:#5ad39d}.sub{font-size:12px;color:#d5eee6;margin-top:2px}" +
                ".arrow{width:0;height:0;border-left:13px solid transparent;border-right:13px solid transparent;border-bottom:38px solid #28a8ff;filter:drop-shadow(0 0 4px #fff)}" +
                ".other{width:24px;height:24px;border-radius:6px;background:#ff4d4d;border:3px solid white;box-shadow:0 0 0 14px rgba(255,77,77,.25)}" +
                ".zone{background:rgba(41,199,164,.18);border:2px solid #29c7a4;border-radius:50%}" +
                "</style></head><body><div id='map'></div><div class='hud'><div id='level' class='level'>LOW</div><div id='subtitle' class='sub'>Waiting for real GPS location</div></div>" +
                "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>" +
                "<script>" +
                "var map=L.map('map',{zoomControl:false}).setView([17.9373,79.8481],16);" +
                "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(map);" +
                "var userIcon=L.divIcon({className:'',html:'<div class=\"arrow\"></div>',iconSize:[32,44],iconAnchor:[16,22]});" +
                "var otherIcon=L.divIcon({className:'',html:'<div class=\"other\"></div>',iconSize:[30,30],iconAnchor:[15,15]});" +
                "var user=null,other=null,zone=null,line=null,centered=false;" +
                "window.updateSafetyMap=function(d){document.getElementById('level').textContent=d.level||'LOW';document.getElementById('level').style.color=d.level==='CRITICAL'?'#ff4d4d':d.level==='HIGH'?'#ff914d':d.level==='MEDIUM'?'#ffcc4d':'#5ad39d';" +
                "document.getElementById('subtitle').textContent=d.hasLocation?('Real map active | '+(d.speedKmh||0)+' km/h'):'Waiting for real GPS location';" +
                "if(d.hasLocation){var p=[d.lat,d.lng];if(!user){user=L.marker(p,{icon:userIcon}).addTo(map).bindTooltip('You');}else{user.setLatLng(p);}if(!centered){map.setView(p,17);centered=true;}else{map.panTo(p,{animate:true,duration:.4});}}" +
                "if(d.hasZone){var zp=[d.zoneLat,d.zoneLng];if(!zone){zone=L.circle(zp,{radius:120,color:'#29c7a4',fillColor:'#29c7a4',fillOpacity:.16}).addTo(map).bindTooltip(d.zoneName);}else{zone.setLatLng(zp);}}" +
                "if(d.hasOther){var op=[d.otherLat,d.otherLng];if(!other){other=L.marker(op,{icon:otherIcon}).addTo(map).bindTooltip('Other app user');}else{other.setLatLng(op);}if(d.hasLocation){if(line)map.removeLayer(line);line=L.polyline([[d.lat,d.lng],op],{color:'#ff4d4d',weight:5,opacity:.85}).addTo(map);}}" +
                "else{if(other){map.removeLayer(other);other=null;}if(line){map.removeLayer(line);line=null;}}" +
                "};" +
                "</script></body></html>";
    }
}

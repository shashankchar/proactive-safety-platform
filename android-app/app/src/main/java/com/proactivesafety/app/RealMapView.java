package com.proactivesafety.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
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

    public void reloadMap() {
        loaded = false;
        loadDataWithBaseURL("https://maps.olakrutrim.com/", html(), "text/html", "UTF-8", null);
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

            JSONArray nearby = new JSONArray();
            if (alert != null && alert.nearbyVehicles != null) {
                for (NearbyVehicle vehicle : alert.nearbyVehicles) {
                    JSONObject item = new JSONObject();
                    item.put("vehicleId", vehicle.vehicleId);
                    item.put("lat", vehicle.latitude);
                    item.put("lng", vehicle.longitude);
                    item.put("speedKmh", vehicle.speedKmh);
                    item.put("heading", vehicle.headingDeg);
                    item.put("hasHeading", vehicle.hasHeading);
                    item.put("ageMs", vehicle.ageMs);
                    item.put("danger", alert.present && alert.otherVehicleId.equals(vehicle.vehicleId));
                    nearby.put(item);
                }
            }
            json.put("nearby", nearby);

            evaluateJavascript("window.updateSafetyMap(" + json + ")", null);
        } catch (Exception ignored) {
        }
    }

    private String html() {
        String olaApiKey = CooperativeSafetyClient.olaApiKey(getContext());
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>" +
                "html,body,#map{height:100%;margin:0;background:#07130f;font-family:Arial,sans-serif;overflow:hidden}" +
                "#map{position:absolute;inset:0}.empty{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;text-align:center;color:#d5eee6;background:linear-gradient(135deg,#07130f,#10231d)}" +
                ".hud{position:absolute;left:12px;right:12px;top:12px;z-index:4;background:rgba(0,0,0,.62);border:1px solid rgba(255,255,255,.14);border-radius:10px;padding:10px 12px;color:white}" +
                ".level{font-weight:900;font-size:24px;color:#5ad39d}.sub{font-size:12px;color:#d5eee6;margin-top:2px}.provider{position:absolute;right:12px;bottom:10px;z-index:4;color:#d5eee6;font-size:11px;background:rgba(0,0,0,.55);padding:5px 8px;border-radius:8px}" +
                ".marker{position:absolute;z-index:3;transform:translate(-50%,-50%);pointer-events:none}.arrow{width:0;height:0;border-left:14px solid transparent;border-right:14px solid transparent;border-bottom:42px solid #28a8ff;filter:drop-shadow(0 0 4px #fff)}" +
                ".otherArrow{width:0;height:0;border-left:11px solid transparent;border-right:11px solid transparent;border-bottom:34px solid #ffcc4d;filter:drop-shadow(0 0 4px rgba(0,0,0,.9))}.otherDanger .otherArrow{border-bottom-color:#ff4d4d;filter:drop-shadow(0 0 6px #ff4d4d)}.otherLabel{position:absolute;left:50%;top:28px;transform:translateX(-50%);white-space:nowrap;color:white;background:rgba(0,0,0,.62);border-radius:8px;padding:2px 6px;font-size:10px;font-weight:700}.zone{width:170px;height:170px;border-radius:50%;border:2px solid #29c7a4;background:rgba(41,199,164,.16)}" +
                ".line{position:absolute;height:4px;background:#ff4d4d;z-index:2;transform-origin:left center;box-shadow:0 0 12px rgba(255,77,77,.9);pointer-events:none}" +
                "</style></head><body><div id='map'></div><div id='empty' class='empty'>Enter Ola Maps API key<br>then start GPS monitoring</div><div class='hud'><div id='level' class='level'>LOW</div><div id='subtitle' class='sub'>Waiting for real GPS location</div></div><div class='provider'>Ola Maps</div>" +
                "<div id='user' class='marker'><div class='arrow'></div></div><div id='other' class='marker'><div class='other'></div></div><div id='zone' class='marker'><div class='zone'></div></div><div id='line' class='line'></div>" +
                "<script src='https://www.unpkg.com/olamaps-web-sdk@latest/dist/olamaps-web-sdk.umd.js'></script>" +
                "<script>" +
                "var OLA_API_KEY=" + JSONObject.quote(olaApiKey) + ";" +
                "var map=null,centered=false,last={},nearbyEls={},userEl=document.getElementById('user'),otherEl=document.getElementById('other'),zoneEl=document.getElementById('zone'),lineEl=document.getElementById('line'),emptyEl=document.getElementById('empty');" +
                "userEl.style.display=otherEl.style.display=zoneEl.style.display=lineEl.style.display='none';" +
                "function setStatus(t){document.getElementById('subtitle').textContent=t;}" +
                "function project(lat,lng){if(!map||!map.project)return null;var p=map.project([lng,lat]);return {x:p.x,y:p.y};}" +
                "function place(el,lat,lng){var p=project(lat,lng);if(!p){el.style.display='none';return null;}el.style.left=p.x+'px';el.style.top=p.y+'px';el.style.display='block';return p;}" +
                "function otherMarker(v){var id=v.vehicleId||('v'+Math.random());var el=nearbyEls[id];if(!el){el=document.createElement('div');el.className='marker';el.innerHTML='<div class=\"otherArrow\"></div><div class=\"otherLabel\"></div>';document.body.appendChild(el);nearbyEls[id]=el;}el.className='marker '+(v.danger?'otherDanger':'');el.querySelector('.otherLabel').textContent=(v.speedKmh||0)+' km/h';return el;}" +
                "function drawNearby(){var keep={};(last.nearby||[]).forEach(function(v){if(v.ageMs>20000)return;var el=otherMarker(v);keep[v.vehicleId]=true;place(el,v.lat,v.lng);if(v.hasHeading){el.style.transform='translate(-50%,-50%) rotate('+v.heading+'deg)';el.querySelector('.otherLabel').style.transform='translateX(-50%) rotate('+(-v.heading)+'deg)';}else{el.style.transform='translate(-50%,-50%)';el.querySelector('.otherLabel').style.transform='translateX(-50%)';}});Object.keys(nearbyEls).forEach(function(id){if(!keep[id]){nearbyEls[id].remove();delete nearbyEls[id];}});}" +
                "function draw(){if(!last.hasLocation)return;var up=place(userEl,last.lat,last.lng);if(last.heading){userEl.style.transform='translate(-50%,-50%) rotate('+last.heading+'deg)';}else{userEl.style.transform='translate(-50%,-50%)';}if(last.hasZone)place(zoneEl,last.zoneLat,last.zoneLng);else zoneEl.style.display='none';drawNearby();if(last.hasOther){var op=project(last.otherLat,last.otherLng);if(up&&op){var dx=op.x-up.x,dy=op.y-up.y,len=Math.sqrt(dx*dx+dy*dy),ang=Math.atan2(dy,dx)*180/Math.PI;lineEl.style.display='block';lineEl.style.left=up.x+'px';lineEl.style.top=up.y+'px';lineEl.style.width=len+'px';lineEl.style.transform='rotate('+ang+'deg)';}}else{lineEl.style.display='none';}}" +
                "async function boot(){if(!OLA_API_KEY){emptyEl.style.display='flex';setStatus('Ola Maps API key required');return;}try{var olaMaps=new OlaMaps({apiKey:OLA_API_KEY});map=await olaMaps.init({style:'https://api.olamaps.io/tiles/vector/v1/styles/default-light-standard/style.json',container:'map',center:[79.8481,17.9373],zoom:16});emptyEl.style.display='none';if(map.on){map.on('move',draw);map.on('zoom',draw);}setStatus('Ola map ready. Waiting for GPS.');draw();}catch(e){emptyEl.style.display='flex';emptyEl.innerHTML='Ola map failed to load<br>Check API key and internet';setStatus('Ola map failed');}}" +
                "window.updateSafetyMap=function(d){document.getElementById('level').textContent=d.level||'LOW';document.getElementById('level').style.color=d.level==='CRITICAL'?'#ff4d4d':d.level==='HIGH'?'#ff914d':d.level==='MEDIUM'?'#ffcc4d':'#5ad39d';" +
                "last=d||{};setStatus(d.hasLocation?('Ola live map | '+(d.speedKmh||0)+' km/h'):(OLA_API_KEY?'Waiting for real GPS location':'Ola Maps API key required'));" +
                "if(map&&d.hasLocation){if(!centered){map.setCenter([d.lng,d.lat]);if(map.setZoom)map.setZoom(17);centered=true;}else if(map.easeTo){map.easeTo({center:[d.lng,d.lat],duration:400});}else{map.setCenter([d.lng,d.lat]);}}draw();" +
                "};" +
                "boot();" +
                "</script></body></html>";
    }
}

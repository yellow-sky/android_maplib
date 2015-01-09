/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2015. NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextgis.maplib.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import com.nextgis.maplib.datasource.GeoEnvelope;
import com.nextgis.maplib.datasource.TileItem;
import com.nextgis.maplib.util.FileUtil;
import com.nextgis.maplib.util.NetworkUtil;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.nextgis.maplib.util.Constants.*;
import static com.nextgis.maplib.util.GeoConstants.MERCATOR_MAX;


public class RemoteTMSLayer
        extends TMSLayer
{
    protected static final String JSON_URL_KEY = "url";
    protected String                      mURL;
    protected NetworkUtil                 mNet;
    protected final List<String> mSubdomains;
    protected String mSubDomainsMask;
    protected int mCurrentSubdomain;


    public RemoteTMSLayer(
            Context context,
            File path)
    {
        super(context, path);

        mNet = new NetworkUtil(context);
        mSubdomains = new ArrayList<>();
        mCurrentSubdomain = 0;
    }


    protected DefaultHttpClient getHttpClient()
    {
        /*HttpParams httpParameters = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(httpParameters, TIMEOUT_CONNECTION);
        // Set the default socket timeout (SO_TIMEOUT)
        // in milliseconds which is the timeout for waiting for data.
        HttpConnectionParams.setSoTimeout(httpParameters, TIMEOUT_SOKET);
        */
        DefaultHttpClient HTTPClient = new DefaultHttpClient();//httpParameters);
        HTTPClient.getParams().setParameter(CoreProtocolPNames.USER_AGENT, APP_USER_AGENT);
        HTTPClient.getParams()
                   .setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, TIMEOUT_CONNECTION);
        HTTPClient.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT, TIMEOUT_SOKET);

        return HTTPClient;
    }



    @Override
    public Bitmap getBitmap(TileItem tile)
    {
        Bitmap ret;
        // try to get tile from local cache
        File tilePath = new File(mPath, tile.toString("{z}/{x}/{y}" + TILE_EXT));
        if (tilePath.exists() && System.currentTimeMillis() - tilePath.lastModified() <
                                 DEFAULT_MAXIMUM_CACHED_FILE_AGE) {
            ret = BitmapFactory.decodeFile(tilePath.getAbsolutePath());
            if (ret != null) {
                return ret;
            }
        }

        if (!mNet.isNetworkAvailable()) { //return tile from cache
            ret = BitmapFactory.decodeFile(tilePath.getAbsolutePath());
            return ret;
        }
        // try to get tile from remote
        String url = tile.toString(getURLSubdomain());
        Log.d(TAG, "url: " + url);
        try {

            final HttpUriRequest head = new HttpGet(url);
            final DefaultHttpClient HTTPClient = getHttpClient();
            final HttpResponse response = HTTPClient.execute(head);

            // Check to see if we got success
            final org.apache.http.StatusLine line = response.getStatusLine();
            if (line.getStatusCode() != 200) {
                Log.d(TAG,
                      "Problem downloading MapTile: " + url + " HTTP response: " +
                      line);
                ret = BitmapFactory.decodeFile(tilePath.getAbsolutePath());
                return ret;
            }

            final HttpEntity entity = response.getEntity();
            if (entity == null) {
                Log.d(TAG, "No content downloading MapTile: " + url);
                ret = BitmapFactory.decodeFile(tilePath.getAbsolutePath());
                return ret;
            }

            FileUtil.createDir(tilePath.getParentFile());

            InputStream input = entity.getContent();
            OutputStream output = new FileOutputStream(tilePath.getAbsolutePath());
            byte data[] = new byte[IO_BUFFER_SIZE];

            FileUtil.copyStream(input, output, data, IO_BUFFER_SIZE);

            output.close();
            input.close();
            ret = BitmapFactory.decodeFile(tilePath.getAbsolutePath());
            return ret;

        } catch (IOException e) {
            Log.d(TAG, "Problem downloading MapTile: " + url + " Error: " +
                       e.getLocalizedMessage());
        }

        ret = BitmapFactory.decodeFile(tilePath.getAbsolutePath());
        return ret;
    }


    @Override
    public JSONObject toJSON()
            throws JSONException
    {
        JSONObject rootConfig = super.toJSON();
        rootConfig.put(JSON_URL_KEY, mURL);
        return rootConfig;
    }


    @Override
    public void fromJSON(JSONObject jsonObject)
            throws JSONException
    {
        super.fromJSON(jsonObject);
        mURL = jsonObject.getString(JSON_URL_KEY);

        analizeURL(mURL);
    }


    @Override
    public int getType()
    {
        return LAYERTYPE_REMOTE_TMS;
    }


    public String getURL()
    {
        return mURL;
    }


    public void setURL(String URL)
    {
        mURL = URL;
        analizeURL(mURL);
    }

    protected synchronized String getURLSubdomain()
    {
        if(mSubdomains.size() == 0 || mSubDomainsMask.length() == 0)
            return mURL;

        if(mCurrentSubdomain >= mSubdomains.size())
            mCurrentSubdomain = 0;

        String subdomain = mSubdomains.get(mCurrentSubdomain++);
        return mURL.replace(mSubDomainsMask, subdomain);
    }

    protected void analizeURL(String url){
        //analize url for subdomains
        boolean begin_block = false;
        String subdomain = "";
        int beginSubDomains = NOT_FOUND;
        int endSubDomains = NOT_FOUND;
        for(int i = 0; i < url.length(); ++i)
        {
            if(begin_block)
            {
                if(url.charAt(i) == 'x' || url.charAt(i) == 'y' || url.charAt(i) == 'z')
                {
                    begin_block = false;
                }
                else if(url.charAt(i) == ',')
                {
                    subdomain = subdomain.trim();
                    if(subdomain.length() > 0) {
                        mSubdomains.add(subdomain);
                        subdomain = "";
                    }
                }
                else if(url.charAt(i) == '}')
                {
                    subdomain = subdomain.trim();
                    if(subdomain.length() > 0) {
                        mSubdomains.add(subdomain);
                        subdomain = "";
                    }
                    endSubDomains = i;
                    begin_block = false;
                }
                else
                {
                    subdomain += url.charAt(i);
                }
            }

            if(url.charAt(i) == '{')
            {
                if(endSubDomains == NOT_FOUND)
                    beginSubDomains = i;
                begin_block = true;
            }
        }

        if(endSubDomains > beginSubDomains)
        {
            mSubDomainsMask = url.substring(beginSubDomains, endSubDomains + 1);
        }
    }

    @Override
    public GeoEnvelope getExtents()
    {
        if(mExtents == null)
            mExtents = new GeoEnvelope(-MERCATOR_MAX, MERCATOR_MAX, -MERCATOR_MAX, MERCATOR_MAX);
        return mExtents;
    }


    @Override
    public int getMaxThreadCount()
    {
        return mSubdomains.size() * HTTP_SEPARATE_THREADS;
    }
}
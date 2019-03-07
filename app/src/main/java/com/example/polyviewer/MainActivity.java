package com.example.polyviewer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Main Activity.
 * <p>
 * This activity creates and adds a MyGLSurfaceView, which handles the rendering.
 * It also makes a request via Poly API for a particular asset, then parses the response to
 * produce the raw data needed by OpenGL to render the asset. When that data is ready, this
 * class feeds that data into the renderer for display.
 * <p>
 * IMPORTANT: before running this sample, enter your project's API key in PolyApi.java.
 */
public class MainActivity extends Activity {
    private static final String TAG = "PolyViewer";

    // The asset ID to download and display.
    private static final String ASSET_ID = "a648BwpXx-A";

    private static final String ASSET_ID_PIANO = "5vbJ5vildOq";

    // The size we want to scale the asset to, for display. This size guarantees that no matter
    // how big or small the asset is, we will scale it to a reasonable size for viewing.
    private static final float ASSET_DISPLAY_SIZE = 10;

    // The GLSurfaceView that renders the object.
    private MyGLSurfaceView glView;

    // Our background thread, which does all of the heavy lifting so we don't block the main thread.
    private HandlerThread backgroundThread;

    // Handler for the background thread, to which we post background thread tasks.
    private Handler backgroundThreadHandler;

    // The AsyncFileDownloader responsible for downloading a set of data files from Poly.
    private AsyncFileDownloader fileDownloader;

    // TextView that displays the status.
    private TextView statusText;

    // Search Editview
    private EditText searchEditText;

    // ListView for hold thumbnails from POLY list api
    private ListView searchListView;

    private List<JSONObject> assetList = new ArrayList<>();

    private LocalArrayAdapter adapter;

    private String mUserSearchString;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set the Activity's layout and get the references to our views.
        setContentView(R.layout.activity_main);
        glView = findViewById(R.id.my_gl_surface_view);
        statusText = findViewById(R.id.status_text);
        searchListView = findViewById(R.id.search_list);
        searchEditText = findViewById(R.id.search_edit_text);

        searchEditText.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    searchEditText.clearFocus();
                    getSearchAsset(searchEditText.getText().toString());
                }
                return false;
            }
        });

        adapter = new LocalArrayAdapter(this, assetList);
        searchListView.setAdapter(adapter);

        searchListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                try {
                    String objectId = adapter.values.get(position).getString("name");
                    String[] arr = objectId.split("/");

                    if(arr.length > 1)
                        downloadAsset(arr[1]);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

            }
        });

        glView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                glView.getRenderer().startAnimation(true);
            }
        });

        downloadAsset(ASSET_ID);
    }

    @Override
    protected void onDestroy() {
        backgroundThread.quit();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        glView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
    }

    void downloadAsset(String assetId) {
        // Create a background thread, where we will do the heavy lifting.
        backgroundThread = new HandlerThread("Worker");
        backgroundThread.start();
        backgroundThreadHandler = new Handler(backgroundThread.getLooper());

        // Request the asset from the Poly API.
        Log.d(TAG, "Requesting asset " + ASSET_ID);
        statusText.setText("Requesting...");
        PolyApi.GetAsset(assetId, backgroundThreadHandler, new AsyncHttpRequest.CompletionListener() {
            @Override
            public void onHttpRequestSuccess(byte[] responseBody) {
                // Successfully fetched asset information. This does NOT include the model's geometry,
                // it's just the metadata. Let's parse it.
                parseAsset(responseBody);
            }

            @Override
            public void onHttpRequestFailure(int statusCode, String message, Exception exception) {
                // Something went wrong with the request.
                handleRequestFailure(statusCode, message, exception);
            }
        });
    }

    void getSearchAsset(String searchString) {
        if (searchString.isEmpty() || searchString.equals(mUserSearchString))
            return;

        mUserSearchString = searchString;

        // Create a background thread, where we will do the heavy lifting.
        backgroundThread = new HandlerThread("Worker");
        backgroundThread.start();
        backgroundThreadHandler = new Handler(backgroundThread.getLooper());
        statusText.setText("Requesting...");

        PolyApi.GetAssetUsingSearchString(mUserSearchString, backgroundThreadHandler, new AsyncHttpRequest.CompletionListener() {
            @Override
            public void onHttpRequestSuccess(byte[] responseBody) {
                // Successfully fetched asset information. This does NOT include the model's geometry,
                // it's just the metadata. Let's parse it.
                parseAssetSearchString(responseBody);
            }

            @Override
            public void onHttpRequestFailure(int statusCode, String message, Exception exception) {
                // Something went wrong with the request.
                handleRequestFailure(statusCode, message, exception);
            }
        });
    }

    private List<String> getAllThumbnail(JSONArray assets) throws JSONException {
        List<String> list = new ArrayList<>();

        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            JSONObject thumbnail = asset.getJSONObject("thumbnail");
            list.add(thumbnail.getString("url"));
        }

        return new ArrayList<>(list);
    }

    private void parseAssetSearchString(byte[] assetData) {
        Log.d(TAG, "Got asset response (" + assetData.length + " bytes). Parsing.");
        String assetBody = new String(assetData, Charset.forName("UTF-8"));
        Log.d(TAG, assetBody);
        try {
            JSONObject response = new JSONObject(assetBody);

            // multiple items can be founds - api returns an array of assets
            JSONArray assets = response.getJSONArray("assets");

            ArrayList<String> thumbnails = (ArrayList<String>) getAllThumbnail(assets);

            // download all thumbnails in the background
            new DownloadImageTask().execute(thumbnails.toArray(new String[0]));

            // upon new search, you now get new assestlist. So, clear the old one
            assetList.clear();

            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                assetList.add(asset);
            }

            // now that we have all the assets; set the list view adpater
            updateAdapater();

        } catch (JSONException jsonException) {
            Log.e(TAG, "JSON parsing error while processing response: " + jsonException);
            jsonException.printStackTrace();
            setStatusMessageOnUiThread("Failed to parse response.");
        }
    }

    // NOTE: this runs on the background thread.
    private void parseAsset(byte[] assetData) {
        Log.d(TAG, "Got asset response (" + assetData.length + " bytes). Parsing.");
        String assetBody = new String(assetData, Charset.forName("UTF-8"));
        Log.d(TAG, assetBody);
        try {
            JSONObject response = new JSONObject(assetBody);
            parseAssetHelper(response);

        } catch (JSONException jsonException) {
            Log.e(TAG, "JSON parsing error while processing response: " + jsonException);
            jsonException.printStackTrace();
            setStatusMessageOnUiThread("Failed to parse response.");
        }
    }

    private void parseAssetHelper(JSONObject response) throws JSONException {
        // Display attribution in a toast, for simplicity. In your app, you don't have to use a
        // toast to do this. You can display it where it's most appropriate for your app.
        String displayName = response.getString("displayName");
        String authorName = response.getString("authorName");

        if(displayName !=null && authorName != null)
            setStatusMessageOnUiThread( displayName + " by " + authorName);

        // The asset may have several formats (OBJ, GLTF, FBX, etc). We will look for the OBJ format.
        JSONArray formats = response.getJSONArray("formats");
        boolean foundObjFormat = false;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.getJSONObject(i);
            if (format.getString("formatType").equals("OBJ")) {
                // Found the OBJ format. The format gives us the URL of the data files that we should
                // download (which include the OBJ file, the MTL file and the textures). We will now
                // request those files.
                requestDataFiles(format);
                foundObjFormat = true;
                break;
            }
        }
        if (!foundObjFormat) {
            // If this happens, it's because the asset doesn't have a representation in the OBJ
            // format. Since this simple sample code can only parse OBJ, we can't proceed.
            // But other formats might be available, so if your client supports multiple formats,
            // you could still try a different format instead.
            Log.e(TAG, "Could not find OBJ format in asset.");
            return;
        }
    }

    // Requests the data files for the OBJ format.
    // NOTE: this runs on the background thread.
    private void requestDataFiles(JSONObject objFormat) throws JSONException {
        // objFormat has the list of data files for the OBJ format (OBJ file, MTL file, textures).
        // We will use a AsyncFileDownloader to download all those files.
        fileDownloader = new AsyncFileDownloader();

        // The "root file" is the OBJ.
        JSONObject rootFile = objFormat.getJSONObject("root");
        fileDownloader.add(rootFile.getString("relativePath"), rootFile.getString("url"));

        // The "resource files" are the MTL file and textures.
        JSONArray resources = objFormat.getJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resourceFile = resources.getJSONObject(i);
            String path = resourceFile.getString("relativePath");
            String url = resourceFile.getString("url");
            // For this example, we only care about OBJ and MTL files (not textures).
            if (path.toLowerCase().endsWith(".obj") || path.toLowerCase().endsWith(".mtl")) {
                fileDownloader.add(path, url);
            }
        }

        // Now start downloading the data files. When this is done, the callback will call
        // processDataFiles().
        Log.d(TAG, "Starting to download data files, # files: " + fileDownloader.getEntryCount());
        fileDownloader.start(backgroundThreadHandler, new AsyncFileDownloader.CompletionListener() {
            @Override
            public void onPolyDownloadFinished(AsyncFileDownloader downloader) {
                if (downloader.isError()) {
                    Log.e(TAG, "Failed to download data files for asset.");
                    setStatusMessageOnUiThread("Failed to download data files.");
                    return;
                }
                processDataFiles();
            }
        });
    }

    // NOTE: this runs on the background thread.
    private void processDataFiles() {
        Log.d(TAG, "All data files downloaded.");
        // At this point, all the necessary data files are downloaded in fileDownloader, so what
        // we have to do now is parse and convert those files to a format we can render.

        ObjGeometry objGeometry = null;
        MtlLibrary mtlLibrary = new MtlLibrary();

        try {
            for (int i = 0; i < fileDownloader.getEntryCount(); i++) {
                AsyncFileDownloader.Entry entry = fileDownloader.getEntry(i);
                Log.d(TAG, "Processing: " + entry.fileName + ", length:" + entry.contents.length);
                String contents = new String(entry.contents, Charset.forName("UTF-8"));
                if (entry.fileName.toLowerCase().endsWith(".obj")) {
                    // It's the OBJ file.
/*                    if (objGeometry != null) {
                        // Shouldn't happen. There should only be one OBJ file.
                        Log.w(TAG, "Package had more than one OBJ file. Ignoring.");
                        continue;
                    }*/
                    objGeometry = ObjGeometry.parse(contents);
                } else if (entry.fileName.toLowerCase().endsWith(".mtl")) {
                    // There can be more than one MTL file. Just add the materials to our library.
                    mtlLibrary.parseAndAdd(contents);
                }
            }

            // We now have the OBJ file in objGeometry and the material library (MTL files) in mtlLibrary.
            // Because OBJs can have any size and the geometry can be at any point that's not necessarily
            // the origin, we apply a translation and scale to make sure it fits in a comfortable
            // bounding box in order for us to display it.
            ObjGeometry.Vec3 boundsCenter = objGeometry.getBoundsCenter();
            ObjGeometry.Vec3 boundsSize = objGeometry.getBoundsSize();
            float maxDimension = Math.max(boundsSize.x, Math.max(boundsSize.y, boundsSize.z));
            float scale = ASSET_DISPLAY_SIZE / maxDimension;
            ObjGeometry.Vec3 translation =
                    new ObjGeometry.Vec3(-boundsCenter.x, -boundsCenter.y, -boundsCenter.z);
            Log.d(TAG, "Will apply translation: " + translation + " and scale " + scale);

            // Now let's generate the raw buffers that the GL thread will use for rendering.
            RawObject rawObject = RawObject.convertObjAndMtl(objGeometry, mtlLibrary, translation, scale);

            // Hand it over to the GL thread for rendering.
            glView.getRenderer().setRawObjectToRender(rawObject);

            // Our job is done. From this point on the GL thread will pick up the raw object and
            // properly create the OpenGL objects to represent it (IBOs, VBOs, etc).
        } catch (ObjGeometry.ObjParseException objParseException) {
            Log.e(TAG, "Error parsing OBJ file.");
            objParseException.printStackTrace();
            setStatusMessageOnUiThread("Failed to parse OBJ file.");
        } catch (MtlLibrary.MtlParseException mtlParseException) {
            Log.e(TAG, "Error parsing MTL file.");
            mtlParseException.printStackTrace();
            setStatusMessageOnUiThread("Failed to parse MTL file.");
        }
    }

    // NOTE: this runs on the background thread.
    private void handleRequestFailure(int statusCode, String message, Exception exception) {
        // NOTE: because this is a simple sample, we don't have any real error handling logic
        // other than just printing the error. In an actual app, this is where you would take
        // appropriate action according to your app's use case. You could, for example, surface
        // the error to the user or retry the request later.
        Log.e(TAG, "Request failed. Status code " + statusCode + ", message: " + message +
                ((exception != null) ? ", exception: " + exception : ""));
        if (exception != null) exception.printStackTrace();
        setStatusMessageOnUiThread("Request failed. See logs.");
    }

    // NOTE: this runs on the background thread.
    private void setStatusMessageOnUiThread(final String statusMessage) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(statusMessage);
            }
        });
    }

    private void updateAdapater() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });
    }

    // download thumbnails here
    private class DownloadImageTask extends AsyncTask<String, Void, HashMap> {
        HashMap<String, Bitmap> bitmapHashMap = new HashMap<>();

        private DownloadImageTask() {

        }

        protected HashMap<String, Bitmap> doInBackground(String... urls) {

            for(String url : urls) {
                Bitmap icon;
                try {
                    InputStream in = new java.net.URL(url).openStream();
                    icon = BitmapFactory.decodeStream(in);

                    bitmapHashMap.put(url, icon);
                } catch (Exception e) {
                    Log.e("Error", e.getMessage());
                    e.printStackTrace();
                }
            }

            return bitmapHashMap;
        }

        protected void onPostExecute(HashMap result) {
            adapter.setThumbnails(result);
            adapter.notifyDataSetChanged();
        }
    }

    private class LocalArrayAdapter extends BaseAdapter {
        private final Context mContext;
        private List<JSONObject> values;
        private HashMap<String, Bitmap> thumbnailHashMap;

        public LocalArrayAdapter(Context context, List<JSONObject> values) {
            this.mContext = context;
            this.values = values;
        }

        public void setThumbnails(HashMap<String, Bitmap> map) {
            this.thumbnailHashMap = map;
        }

        @Override
        public int getCount() {
            return values.size();
        }

        @Override
        public Object getItem(int position) {
            return values.get(position);
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            if(convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(mContext);
                convertView = inflater.inflate(R.layout.search_item, parent, false);
                final ImageView iv = convertView.findViewById(R.id.icon);
                final LocalArrayAdapter.ViewHolder viewHolder = new LocalArrayAdapter.ViewHolder(iv);
                convertView.setTag(viewHolder);
            }


            LocalArrayAdapter.ViewHolder viewHolder = (LocalArrayAdapter.ViewHolder) convertView.getTag();

            try {
                if(thumbnailHashMap != null && thumbnailHashMap.containsKey(getUrl(position)))
                    viewHolder.iv.setImageBitmap(thumbnailHashMap.get(getUrl(position)));
            } catch (JSONException e) {
                e.printStackTrace();
            }

            return convertView;
        }

        private String getUrl(int pos) throws JSONException{
            JSONObject thumbnail = values.get(pos).getJSONObject("thumbnail");
            return thumbnail.getString("url");
        }

        private class ViewHolder {
            private final ImageView iv;

            public ViewHolder(ImageView iv) {
                this.iv = iv;
            }
        }
    }
}

package com.example.ramon.drivejavaclient;


import android.Manifest;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

/****************************************************************************************************
 *                                          DriveJavaClient
 * The purpose of this app is to search google drive files CV's containing user specified keywords.
 * The current status is:
 *      - The app has permissions to open and download all files from the users
 *        google drive account.
 *      - The app has permissions to read drive file meta data
 *      - The app has permissions to read and write to external storage
 *      - Opening / download files is prepared, there are function calls for both functionalities
 *      - The user can search for keywords and get a list of results displayed
 *      - Clicking on an element in said list will either download or open the corresponding file
 *
 *  To be useful the app must implement:
 *      - Filter results not located in the folder for CV's (Avalon have a drive folder for CV's)
 *      - Add parser for user input, implement boolean functions such that user query "C/C++ asm"
 *        returns result for "C/C++" AND "asm". The google Api supports this, but it must be implemented
 *        on client side
 *
 *  To be awesome the app should:
 *      - User functionality to save interesting material in new folder
 *      - Log all user queries, could give some cool data on what qualifications avalon needs
 *        (maybe android programmers?)
 *      - Rework ui so user doesn't get cancer from prolonged use
 *
 *  Readability would benefit from breaking up functionality in different files/modules.
 *  The onCreate method is to big, should be broken up into separate methods and not nested classes
 *
 *  Gradle:
 *      - Not all of google services is included as android has a 64k method cap
 *        just copy my gradle file and everything should work
 *
 *  Pretty much everything concerning account management is taken from developers.google.com
 *  If you change package name you will have to set up a new project at console.developers.google.com
 *  by supplying packagename, sha1 key and enabling the cloud api
 *  Note that if you're using windows, then the keytool.exe is located in your jdk install path
 *  default is c:\"program files"\java\jdk.<version>\bin\
 *
 ****************************************************************************************************/
public class MainActivity extends Activity implements
    EasyPermissions.PermissionCallbacks {
    /****************************************************************************************************
     * Class members
     * Some of these could probably be local, but the current implementation is easy to code
     ****************************************************************************************************/
    GoogleAccountCredential mCredential;//Used for logging into google
    private TextView mOutputText;       //Used for status echo to user
    private Button mSearchButton;       //well this should be rather self documenting :)
    private EditText mTextInput;        //User input field
    ProgressDialog mProgress;           //Not super useful but shows progress (duh)
    private String mInput;              //Current user input, this could be handled locally
    private ListView mResultList;       //List in the ui
    private List<File> mFiles;          //Returned files, mService can handle all functionnallity, remove this
    private com.google.api.services.drive.Drive mService = null;    //kinda pointer to results from drive

    /****************************************************************************************************
     * Const
     ****************************************************************************************************/
    //Used to figure out which method was caller
    static final int REQUEST_ACCOUNT_PICKER = 1000;
    static final int REQUEST_AUTHORIZATION = 1001;
    static final int REQUEST_GOOGLE_PLAY_SERVICES = 1002;
    static final int REQUEST_PERMISSION_GET_ACCOUNTS = 1003;
    //Strings used in the ui
    private static final String BUTTON_TEXT = "Search";
    private static final String TEXT_INPUT_MSG ="Enter search parameter(s) here";
    //Permissions
    private static final String PREF_ACCOUNT_NAME = "accountName";
    private static final String[] SCOPES  = {DriveScopes.DRIVE_METADATA_READONLY, DriveScopes.DRIVE};
    private static final String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE};

    /****************************************************************************************************
     * @param Bundle savedInstanceState - Not used
     * @returns void
     * First method to be called when new instance of app is created, later onResume is called by the OS
     * All initalization of ui members and user input callback is done here
     ****************************************************************************************************/
    @Override
    protected void onCreate(Bundle savedInstanceState){
        //Initialize ui components and set callbacks
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTextInput = (EditText)findViewById(R.id.textInput);
        mTextInput.setOnEditorActionListener(new TextView.OnEditorActionListener(){
            /****************************************************************************************************
             *
             * @param TextView v    - Points to the user input text field
             * @param int actionId  - Not used
             * @param KeyEvent event - Test for enter down
             * @return bool         - not used
             *
             * Runs when user presses enter on the keyboard. User could also press the search button
             * Hides the keyboard after enter.
             ****************************************************************************************************/
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event){
               boolean handled = false;
               if(event.getAction() == KeyEvent.ACTION_DOWN &&
                       event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                   mInput = v.getText().toString();
                   //echo to user (TODO remove from production)
                   Toast.makeText(getApplicationContext(),mInput,Toast.LENGTH_LONG)
                           .show();
                   v.setText("");
                   //hides keyboard
                   InputMethodManager imm = (InputMethodManager)
                           getApplicationContext().
                                   getSystemService(Context.INPUT_METHOD_SERVICE);
                   imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(),
                           InputMethodManager.HIDE_NOT_ALWAYS);
                   //init query
                   getResultFromApi();
                   handled = true;

               }

               return handled;
           }
        });

        mSearchButton = (Button)findViewById(R.id.searchButton);
        mSearchButton.setOnClickListener(new View.OnClickListener(){
            /****************************************************************************************************
             * @param View v
             * Pretty much same as the text input callback
             ****************************************************************************************************/
            @Override
            public void onClick(View v){
                mSearchButton.setEnabled(false);
                mInput = mTextInput.getText().toString();
                mTextInput.setText("");
                mOutputText.setText("");
                getResultFromApi();
                mSearchButton.setEnabled(true);
            }
        });

        mOutputText = (TextView)findViewById(R.id.statusText);
        mOutputText.setText("Click the \'" + BUTTON_TEXT +"\' button to test");

        //TODO clean this up
        mResultList = (ListView) findViewById(R.id.resultList);
        mResultList.setOnItemClickListener(new AdapterView.OnItemClickListener(){
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                File file = mFiles.get(position);
                Toast.makeText(getApplicationContext(),
                        file.getTitle().toString(),Toast.LENGTH_LONG).show();
                String urlString = "https://docs.google.com/document/d/"
                        + file.getId()
                        //+ "/edit?usp=sharing";
                        +((file.getMimeType().equals("application/vnd.google-apps.document"))?"/export?format=pdf":"");
                String title = file.getTitle();

                /*!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                * Design choice here, open documents with drive app
                * Or download file to external storage
                * Both solutions are prepared:
                * - Call startActivity to open  google drive
                * (or rather let the user choose default app
                * - Call downloadFile to download file to external storage
                *!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!*/

                //open with google drive
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(urlString));
                //startActivity(i);

                //download file (starts new thread)
                downloadFile(file.getId(),title,((file.getMimeType().equals("application/vnd.google-apps.document")?false:true)));
            }
        });

        mProgress = new ProgressDialog(this);
        mProgress.setMessage("Searching");

        //this does some special google magic
        mCredential  = GoogleAccountCredential.usingOAuth2(
                getApplicationContext(), Arrays.asList(SCOPES))
                .setBackOff(new ExponentialBackOff());

    }

    /****************************************************************************************************
     *
     * @param fileId
     * @param fileName
     * @param isPdf
     *
     * Android doensn't allow network work on the main ui thread, creates a new thread for the download
     ****************************************************************************************************/
    private void downloadFile(String fileId, String fileName,boolean isPdf){
        //nested class that will be instantiated to a new thread
        class BackgroundDownload implements Runnable{
            String mFileId;
            String mFileName;
            boolean mIsPdf;
            public BackgroundDownload(String fileId, String fileName,boolean isPdf){
                mFileId = fileId;
                mFileName = fileName;
                mIsPdf = isPdf;
            }

            @Override
            public void run() {
                //API>=23 requires permissions to be requested from within app, check
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    int permission = checkSelfPermission(
                            Manifest.permission
                                    .WRITE_EXTERNAL_STORAGE);
                    //get permissions
                    if (permission != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(PERMISSIONS_STORAGE, 1);
                    }
                }
                //almost all called methods can throw IOException
                try {
                    //create new file on disk
                    java.io.File fileOnDisk = new java.io.File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS),
                            mFileName + (mIsPdf ? "" : ".pdf"));

                    fileOnDisk.createNewFile();
                    OutputStream oStream = new FileOutputStream(fileOnDisk);
                    //if requested file is pdf then just do bytewise copy
                    if(mIsPdf){
                        mService.files().get(mFileId).executeMediaAndDownloadTo(oStream);
                        oStream.close();
                    }else{
                        //export to pdf then download
                        mService.files().export(mFileId,"application/pdf")
                                .executeMediaAndDownloadTo(oStream);
                        oStream.close();
                    }
                    Log.i("Download","Done");
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
        }
        Thread thread = new Thread(new BackgroundDownload(fileId, fileName, isPdf));
        thread.start();
    }

    /****************************************************************************************************
     * @param void
     * Interface to google api. If device can connect to google services then a new async task
     * is created.
     ****************************************************************************************************/
    private void getResultFromApi(){
        if(!isGooglePlayServicesAvailable())
            aquireGooglePlayServices();
        else if(mCredential.getSelectedAccountName() == null)
            chooseAccount();
        else if(!isDeviceOnline())
            mOutputText.setText("No network connection available");
        else
            new MakeRequestTask(mCredential).execute();
    }

    private boolean isDeviceOnline(){
        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return (networkInfo != null && networkInfo.isConnected());
    }

    @AfterPermissionGranted(REQUEST_PERMISSION_GET_ACCOUNTS)
    private void chooseAccount(){
        if(EasyPermissions.hasPermissions(
                this, Manifest.permission.GET_ACCOUNTS)) {
            String accountName = getPreferences(Context.MODE_PRIVATE)
                    .getString(PREF_ACCOUNT_NAME, null);
            if(accountName!=null){
                mCredential.setSelectedAccountName(accountName);
                getResultFromApi();
            }else{
                startActivityForResult(
                        mCredential.newChooseAccountIntent(),
                        REQUEST_ACCOUNT_PICKER);
            }
        }else{
            //request the get acoount permission via user dialog
            EasyPermissions.requestPermissions(
                    this,
                    "This app needs to access your google account",
                    REQUEST_PERMISSION_GET_ACCOUNTS,
                    Manifest.permission.GET_ACCOUNTS);
        }
    }
    //callback for accoutPicker and authorization
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        switch(requestCode){
            case REQUEST_GOOGLE_PLAY_SERVICES:
                if(resultCode != RESULT_OK){
                    mOutputText.setText("This app requires Google Play Services." +
                            "Please install Google Play Services and restart" +
                            "this app");
                }else{
                    getResultFromApi();
                }
                break;

            case REQUEST_ACCOUNT_PICKER:
                if(resultCode == RESULT_OK && data!= null
                        && data.getExtras()!=null){
                    String accountName = data.getStringExtra(AccountManager
                    .KEY_ACCOUNT_NAME);
                    if(accountName!=null){
                        SharedPreferences settings =
                                getPreferences(Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = settings.edit();
                        editor.putString(PREF_ACCOUNT_NAME,accountName);
                        editor.apply();
                        mCredential.setSelectedAccountName(accountName);
                        getResultFromApi();
                    }
                }
                break;

            case REQUEST_AUTHORIZATION:
                if(resultCode == RESULT_OK){
                    getResultFromApi();
                }
                break;
        }
    }


    private boolean isGooglePlayServicesAvailable(){
        GoogleApiAvailability apiAvailability =
                GoogleApiAvailability.getInstance();
        final int connectionStatusCode =
                apiAvailability.isGooglePlayServicesAvailable(this);
        return connectionStatusCode == ConnectionResult.SUCCESS;
    }

    private void aquireGooglePlayServices(){
        GoogleApiAvailability apiAvailability =
                GoogleApiAvailability.getInstance();
        final int connectionStatusCode =
                apiAvailability.isGooglePlayServicesAvailable(this);
        if(apiAvailability.isUserResolvableError(connectionStatusCode))
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode);
    }

    void showGooglePlayServicesAvailabilityErrorDialog(final int connectionStatus){
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        Dialog dialog = apiAvailability.getErrorDialog(
                MainActivity.this,
                connectionStatus,
                REQUEST_GOOGLE_PLAY_SERVICES);
        dialog.show();
    }



    @Override
    public void onPermissionsGranted(int requestCode, List<String> perms) {
        //TODO check what this is supposed to be used for
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> perms) {
        //TODO check what this is supposed to be used for
    }

    /****************************************************************************************************
     * Async task. Sends query to google drive api and populates mResult list with the returned
     * results.
     ****************************************************************************************************/
    private class MakeRequestTask extends AsyncTask<Void, Void, List<String>>{

        private Exception mLastError = null;

        public MakeRequestTask(GoogleAccountCredential credential){
            HttpTransport transport = AndroidHttp.newCompatibleTransport();
            JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
            mService = new com.google.api.services.drive.Drive.Builder(
                    transport,jsonFactory,credential)
                    .setApplicationName("DriveJavaClient")
                    .build();
        }

        protected List<String> doInBackground(Void... params){
            try{
                return getDataFromApi();
            }catch(Exception e){
                mLastError = e;
                cancel(true);
                return null;
            }
        }

        private List<String> getDataFromApi()throws IOException {
            //get list of 10 files
            List<String> fileInfo = new ArrayList<String>();
            FileList result = mService.files().list()
                    .setQ("fullText contains '"+mInput+"'")
                    .execute();

            //mResultList.setAdapter(null);
            mFiles = new ArrayList<>();

            List<File> files = result.getItems();
            if(files!=null){
                for(File file : files) {
                    fileInfo.add(String.format(
                            "%s", file.getTitle()));
                    mFiles.add(file);
                }
            }
            return fileInfo;
        }

        @Override
        protected void onPreExecute(){
            mOutputText.setText("");
            mProgress.show();
        }

        @Override
        protected void onPostExecute(List<String> output){
            mProgress.hide();
            if(output==null || output.size()==0)
                mOutputText.setText("No results found");
            else{
                mOutputText.setText("Returned with results");
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        getApplicationContext(),R.layout.list_black_text,
                        output);
                mResultList.setAdapter(null);
                mResultList.setAdapter(adapter);
                //output.add(0,"Data retrieved using the Drive API");
                //mOutputText.setText(TextUtils.join("\n",output));
            }
        }

        protected void onCancelled(){
            mProgress.hide();
            if(mLastError!= null ){
                if(mLastError instanceof GooglePlayServicesAvailabilityIOException)
                    showGooglePlayServicesAvailabilityErrorDialog(
                            ((GooglePlayServicesAvailabilityIOException)
                            mLastError).getConnectionStatusCode());
                else if(mLastError instanceof UserRecoverableAuthIOException)
                    startActivityForResult(((
                            UserRecoverableAuthIOException)mLastError).getIntent(),
                            MainActivity.REQUEST_AUTHORIZATION);
                else
                    mOutputText.setText("The following error occured:\n"+
                    mLastError.getMessage());
            }else
                mOutputText.setText("Request Cancelled");
        }
    }

}

//
// This is a modified version of Bazel source code. Its copyright lines follow below.
//

// Copyright 2015 Xamarin Inc. All rights reserved.

//
// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package mono.android;

import mono.android.incrementaldeployment.IncrementalClassLoader;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Collection;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.*;
import android.util.Log;
import android.util.ArrayMap;
import android.os.Build;
import dalvik.system.BaseDexClassLoader;

public class MultiDexLoader extends ContentProvider {
	
	@Override
	public boolean onCreate ()
	{
		return true;
	}

	@Override
	public void attachInfo (android.content.Context context, android.content.pm.ProviderInfo info) {
		mIncrementalDeploymentDir = getIncrementalDeploymentDir (context);
		externalResourceFile = getExternalResourceFile ();

		File codeCacheDir = context.getCacheDir ();
		String nativeLibDir = context.getApplicationInfo ().nativeLibraryDir;
		String dataDir = context.getApplicationInfo ().dataDir;
		String packageName = context.getPackageName ();

		List<String> dexes = getDexList (packageName);
		if (dexes != null && dexes.size () > 0) {
			IncrementalClassLoader.inject (
				MultiDexLoader.class.getClassLoader (),
				packageName,
				codeCacheDir,
				nativeLibDir,
				dexes);
		}
		monkeyPatchExistingResources (context);
		super.attachInfo (context, info);
	}


	private String mIncrementalDeploymentDir;
	private String externalResourceFile;

	
	private static String getIncrementalDeploymentDir (Context context)
	{
		// For initial setup by Seppuku, it needs to create the dex deployment directory at app bootstrap.
		// dex is special, important for mono runtime bootstrap.
		String dir = new File (
			android.os.Environment.getExternalStorageDirectory (),
			"Android/data/" + context.getPackageName ()).getAbsolutePath ();
		dir = new File (dir).exists () ?
			dir + "/files" :
			"/data/data/" + context.getPackageName () + "/files";
		String dexDir = dir + "/.__override__/dexes";
		if (!new File (dexDir).exists ())
			new File (dexDir).mkdirs ();
		return dir + "/";
	}

	private String getExternalResourceFile () {
		String base = mIncrementalDeploymentDir;
		String resourceFile = base + ".__override__/packaged_resources";
		if (!(new File (resourceFile).isFile ())) {
			resourceFile = base + ".__override__/resources";
			if (!(new File (resourceFile).isDirectory ())) {
				Log.v ("MultiDexLoader", "Cannot find external resources, not patching them in");
				return null;
			}
		}

		Log.v ("MultiDexLoader", "Found external resources at " + resourceFile);
		return resourceFile;
	}

	private List<String> getDexList (String packageName) {
		List<String> result = new ArrayList<String> ();
		String dexDirectory = mIncrementalDeploymentDir + ".__override__/dexes";
		File[] dexes = new File (dexDirectory).listFiles ();
		// It is not illegal state when it was launched to start Seppuku
		if (dexes == null) {
			return null;
		} else {
			for (File dex : dexes) {
				if (dex.getName ().endsWith (".dex")) {
					result.add (dex.getPath ());
				}
			}
		}

		return result;
	}

	private void monkeyPatchExistingResources (android.content.Context context) {
		if (externalResourceFile == null) {
			return;
		}

		try {
			// Create a new AssetManager instance and point it to the resources installed under
			// /sdcard
			AssetManager newAssetManager = AssetManager.class.getConstructor ().newInstance ();
			Method mAddAssetPath = AssetManager.class.getDeclaredMethod ("addAssetPath", String.class);
			mAddAssetPath.setAccessible (true);
			if (((int) (Integer) mAddAssetPath.invoke (newAssetManager, externalResourceFile)) == 0) {
				throw new IllegalStateException ("Could not create new AssetManager");
			}

			// Kitkat needs this method call, Lollipop doesn't. However, it doesn't seem to cause any harm
			// in L, so we do it unconditionally.
			Method mEnsureStringBlocks = AssetManager.class.getDeclaredMethod ("ensureStringBlocks");
			mEnsureStringBlocks.setAccessible (true);
			mEnsureStringBlocks.invoke (newAssetManager);

			// Find the singleton instance of ResourcesManager
			Collection<WeakReference<Resources>> references;
			if (Build.VERSION.SDK_INT >= 19) {
				Class<?> resourcesManagerClass = Class.forName ("android.app.ResourcesManager");
				Method mGetInstance = resourcesManagerClass.getDeclaredMethod ("getInstance");
				mGetInstance.setAccessible (true);
				Object resourcesManager = mGetInstance.invoke (null, new Object[0]);
				try {
					Field fMActiveResources = resourcesManagerClass.getDeclaredField ("mActiveResources");
					fMActiveResources.setAccessible (true);

					ArrayMap<?, WeakReference<Resources>> arrayMap = (ArrayMap) fMActiveResources.get (resourcesManager);
					references = arrayMap.values ();
				} catch (NoSuchFieldException ignore) {
					Field mResourceReferences = resourcesManagerClass.getDeclaredField ("mResourceReferences");
					mResourceReferences.setAccessible (true);
					references = (Collection) mResourceReferences.get (resourcesManager);
				}
			} else {
				Class<?> activityThread = Class.forName ("android.app.ActivityThread");
				Field fMActiveResources = activityThread.getDeclaredField ("mActiveResources");
				fMActiveResources.setAccessible (true);
				Object thread = getActivityThread (context, activityThread);
				HashMap<?, WeakReference<Resources>> map = (HashMap) fMActiveResources.get (thread);
				references = map.values ();
			}  
			for (WeakReference<Resources> wr : references) {
				Resources resources = (Resources) wr.get ();
				if (resources != null) {
					try {
						Field mAssets = Resources.class.getDeclaredField ("mAssets");
						mAssets.setAccessible (true);
						mAssets.set (resources, newAssetManager);
					} catch (Throwable ignore) {
						Field mResourcesImpl = Resources.class.getDeclaredField ("mResourcesImpl");
						mResourcesImpl.setAccessible (true);
						Object resourceImpl = mResourcesImpl.get (resources);
						Field implAssets = resourceImpl.getClass ().getDeclaredField ("mAssets");
						implAssets.setAccessible (true);
						implAssets.set (resourceImpl, newAssetManager);
					}
					resources.updateConfiguration (resources.getConfiguration (), resources.getDisplayMetrics ());
				}
			}
		} catch (Throwable e) {
			throw new IllegalStateException (e);
		}
	}

	private static Object getActivityThread (Context context, Class<?> activityThread) {
		try {
			if (activityThread == null) {
				activityThread = Class.forName ("android.app.ActivityThread");
			}
			Method m = activityThread.getMethod ("currentActivityThread", new Class[0]);
			m.setAccessible (true);
			Object currentActivityThread = m.invoke (activityThread, new Object[0]);
			Object apk = null;
			Field mActivityThreadField = null;
			if ((currentActivityThread == null) && (context != null)) {
				Field mLoadedApk = context.getClass ().getField ("mLoadedApk");
				mLoadedApk.setAccessible (true);
				apk = mLoadedApk.get (context);
				mActivityThreadField = apk.getClass ().getDeclaredField ("mActivityThread");
				mActivityThreadField.setAccessible (true);
			}
			return mActivityThreadField.get (apk);
		} catch (Throwable ignore) {
		}
		return null;
	}
	
	// ---
	@Override
	public android.database.Cursor query (android.net.Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder)
	{
		throw new RuntimeException ("This operation is not supported.");
	}

	@Override
	public String getType (android.net.Uri uri)
	{
		throw new RuntimeException ("This operation is not supported.");
	}

	@Override
	public android.net.Uri insert (android.net.Uri uri, android.content.ContentValues initialValues)
	{
		throw new RuntimeException ("This operation is not supported.");
	}

	@Override
	public int delete (android.net.Uri uri, String where, String[] whereArgs)
	{
		throw new RuntimeException ("This operation is not supported.");
	}

	@Override
	public int update (android.net.Uri uri, android.content.ContentValues values, String where, String[] whereArgs)
	{
		throw new RuntimeException ("This operation is not supported.");
	}
}

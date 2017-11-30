//
// This is a modified version of Bazel source code. Its copyright lines follow below.
//

// Copyright 2015 Xamarin Inc. All rights reserved.
// Copyright 2017 Microsoft Corporation. All rights reserved.

//
// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//	http://www.apache.org/licenses/LICENSE-2.0
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

import android.app.Activity;
import android.content.*;
import android.util.Log;
import android.util.ArrayMap;
import android.os.Build;
import dalvik.system.BaseDexClassLoader;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;

public class MultiDexLoader extends ContentProvider {
	
	@Override
	public boolean onCreate ()
	{
		return true;
	}

	@Override
	public void attachInfo (android.content.Context context, android.content.pm.ProviderInfo info)
	{
		mIncrementalDeploymentDir = getIncrementalDeploymentDir (context);
		String externalResourceFile = getExternalResourceFile ();

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
		monkeyPatchExistingResources (context, externalResourceFile, getActivities (context, false));
		super.attachInfo (context, info);
	}

	private String mIncrementalDeploymentDir;

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

	private List<String> getDexList (String packageName)
	{
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

	private void monkeyPatchExistingResources(Context context,
			String externalResourceFile,
			Collection<Activity> activities)
	{
		if (externalResourceFile == null) {
			return;
		}
		try {
			// Create a new AssetManager instance and point it to the resources installed under
			// /sdcard
			AssetManager newAssetManager = AssetManager.class.getConstructor().newInstance();
			Method mAddAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
			mAddAssetPath.setAccessible(true);
			if (((Integer) mAddAssetPath.invoke(newAssetManager, externalResourceFile)) == 0) {
				throw new IllegalStateException("Could not create new AssetManager");
			}
			// Kitkat needs this method call, Lollipop doesn't. However, it doesn't seem to cause any harm
			// in L, so we do it unconditionally.
			Method mEnsureStringBlocks = AssetManager.class.getDeclaredMethod("ensureStringBlocks");
			mEnsureStringBlocks.setAccessible(true);
			mEnsureStringBlocks.invoke(newAssetManager);
			if (activities != null) {
				for (Activity activity : activities) {
					Resources resources = activity.getResources();
					try {
						Field mAssets = Resources.class.getDeclaredField("mAssets");
						mAssets.setAccessible(true);
						mAssets.set(resources, newAssetManager);
					} catch (Throwable ignore) {
						Field mResourcesImpl = Resources.class.getDeclaredField("mResourcesImpl");
						mResourcesImpl.setAccessible(true);
						Object resourceImpl = mResourcesImpl.get(resources);
						Field implAssets = resourceImpl.getClass().getDeclaredField("mAssets");
						implAssets.setAccessible(true);
						implAssets.set(resourceImpl, newAssetManager);
					}
					Resources.Theme theme = activity.getTheme();
					try {
						try {
							Field ma = Resources.Theme.class.getDeclaredField("mAssets");
							ma.setAccessible(true);
							ma.set(theme, newAssetManager);
						} catch (NoSuchFieldException ignore) {
							Field themeField = Resources.Theme.class.getDeclaredField("mThemeImpl");
							themeField.setAccessible(true);
							Object impl = themeField.get(theme);
							Field ma = impl.getClass().getDeclaredField("mAssets");
							ma.setAccessible(true);
							ma.set(impl, newAssetManager);
						}
						Field mt = ContextThemeWrapper.class.getDeclaredField("mTheme");
						mt.setAccessible(true);
						mt.set(activity, null);
						Method mtm = ContextThemeWrapper.class.getDeclaredMethod("initializeTheme");
						mtm.setAccessible(true);
						mtm.invoke(activity);
						if (Build.VERSION.SDK_INT < 24) { // As of API 24, mTheme is gone (but updates work
											// without these changes
							Method mCreateTheme = AssetManager.class
									.getDeclaredMethod("createTheme");
							mCreateTheme.setAccessible(true);
							Object internalTheme = mCreateTheme.invoke(newAssetManager);
							Field mTheme = Resources.Theme.class.getDeclaredField("mTheme");
							mTheme.setAccessible(true);
							mTheme.set(theme, internalTheme);
						}
					} catch (Throwable e) {
						Log.e("MultiDexLoader", "Failed to update existing theme for activity " + activity,
								e);
					}
					pruneResourceCaches(resources);
				}
			}
			// Iterate over all known Resources objects
			Collection<WeakReference<Resources>> references;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				// Find the singleton instance of ResourcesManager
				Class<?> resourcesManagerClass = Class.forName("android.app.ResourcesManager");
				Method mGetInstance = resourcesManagerClass.getDeclaredMethod("getInstance");
				mGetInstance.setAccessible(true);
				Object resourcesManager = mGetInstance.invoke(null);
				try {
					Field fMActiveResources = resourcesManagerClass.getDeclaredField("mActiveResources");
					fMActiveResources.setAccessible(true);
					@SuppressWarnings("unchecked")
					ArrayMap<?, WeakReference<Resources>> arrayMap =
							(ArrayMap<?, WeakReference<Resources>>) fMActiveResources.get(resourcesManager);
					references = arrayMap.values();
				} catch (NoSuchFieldException ignore) {
					Field mResourceReferences = resourcesManagerClass.getDeclaredField("mResourceReferences");
					mResourceReferences.setAccessible(true);
					//noinspection unchecked
					references = (Collection<WeakReference<Resources>>) mResourceReferences.get(resourcesManager);
				}
			} else {
				Class<?> activityThread = Class.forName("android.app.ActivityThread");
				Field fMActiveResources = activityThread.getDeclaredField("mActiveResources");
				fMActiveResources.setAccessible(true);
				Object thread = MultiDexLoader.getActivityThread(context, activityThread);
				@SuppressWarnings("unchecked")
				HashMap<?, WeakReference<Resources>> map =
						(HashMap<?, WeakReference<Resources>>) fMActiveResources.get(thread);
				references = map.values();
			}
			for (WeakReference<Resources> wr : references) {
				Resources resources = wr.get();
				if (resources != null) {
					// Set the AssetManager of the Resources instance to our brand new one
					try {
						Field mAssets = Resources.class.getDeclaredField("mAssets");
						mAssets.setAccessible(true);
						mAssets.set(resources, newAssetManager);
					} catch (Throwable ignore) {
						Field mResourcesImpl = Resources.class.getDeclaredField("mResourcesImpl");
						mResourcesImpl.setAccessible(true);
						Object resourceImpl = mResourcesImpl.get(resources);
						Field implAssets = resourceImpl.getClass().getDeclaredField("mAssets");
						implAssets.setAccessible(true);
						implAssets.set(resourceImpl, newAssetManager);
					}
					resources.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
				}
			}
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	private static Object getActivityThread (Context context, Class<?> activityThread)
	{
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

	private static void pruneResourceCaches(Object resources)
	{
		// Drain TypedArray instances from the typed array pool since these can hold on
		// to stale asset data
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			try {
				Field typedArrayPoolField =
						Resources.class.getDeclaredField("mTypedArrayPool");
				typedArrayPoolField.setAccessible(true);
				Object pool = typedArrayPoolField.get(resources);
				Class<?> poolClass = pool.getClass();
				Method acquireMethod = poolClass.getDeclaredMethod("acquire");
				acquireMethod.setAccessible(true);
				while (true) {
					Object typedArray = acquireMethod.invoke(pool);
					if (typedArray == null) {
						break;
					}
				}
			} catch (Throwable ignore) {
			}
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			// Really should only be N; fix this as soon as it has its own API level
			try {
				Field mResourcesImpl = Resources.class.getDeclaredField("mResourcesImpl");
				mResourcesImpl.setAccessible(true);
				// For the remainder, use the ResourcesImpl instead, where all the fields
				// now live
				resources = mResourcesImpl.get(resources);
			} catch (Throwable ignore) {
			}
		}
		// Prune bitmap and color state lists etc caches
		Object lock = null;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
			try {
				Field field = resources.getClass().getDeclaredField("mAccessLock");
				field.setAccessible(true);
				lock = field.get(resources);
			} catch (Throwable ignore) {
			}
		} else {
			try {
				Field field = Resources.class.getDeclaredField("mTmpValue");
				field.setAccessible(true);
				lock = field.get(resources);
			} catch (Throwable ignore) {
			}
		}
		if (lock == null) {
			lock = MultiDexLoader.class;
		}
		//noinspection SynchronizationOnLocalVariableOrMethodParameter
		synchronized (lock) {
			// Prune bitmap and color caches
			pruneResourceCache(resources, "mDrawableCache");
			pruneResourceCache(resources,"mColorDrawableCache");
			pruneResourceCache(resources,"mColorStateListCache");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				pruneResourceCache(resources, "mAnimatorCache");
				pruneResourceCache(resources, "mStateListAnimatorCache");
			}
		}
	}

	private static boolean pruneResourceCache(Object resources, String fieldName)
	{
		try {
			Class<?> resourcesClass = resources.getClass();
			Field cacheField;
			try {
				cacheField = resourcesClass.getDeclaredField(fieldName);
			} catch (NoSuchFieldException ignore) {
				cacheField = Resources.class.getDeclaredField(fieldName);
			}
			cacheField.setAccessible(true);
			Object cache = cacheField.get(resources);
			// Find the class which defines the onConfigurationChange method
			Class<?> type = cacheField.getType();
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
				if (cache instanceof SparseArray) {
					((SparseArray) cache).clear();
					return true;
				} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH && cache instanceof LongSparseArray) {
					// LongSparseArray has API level 16 but was private (and available inside
					// the framework) in 15 and is used for this cache.
					//noinspection AndroidLintNewApi
					((LongSparseArray) cache).clear();
					return true;
				}
			} else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
				// JellyBean, KitKat, Lollipop
				if ("mColorStateListCache".equals(fieldName)) {
					// For some reason framework doesn't call clearDrawableCachesLocked on
					// this field
					if (cache instanceof LongSparseArray) {
						//noinspection AndroidLintNewApi
						((LongSparseArray)cache).clear();
					}
				} else if (type.isAssignableFrom(ArrayMap.class)) {
					Method clearArrayMap = Resources.class.getDeclaredMethod(
							"clearDrawableCachesLocked", ArrayMap.class, Integer.TYPE);
					clearArrayMap.setAccessible(true);
					clearArrayMap.invoke(resources, cache, -1);
					return true;
				} else if (type.isAssignableFrom(LongSparseArray.class)) {
					Method clearSparseMap = Resources.class.getDeclaredMethod(
							"clearDrawableCachesLocked", LongSparseArray.class, Integer.TYPE);
					clearSparseMap.setAccessible(true);
					clearSparseMap.invoke(resources, cache, -1);
					return true;
				}
			} else {
				// Marshmallow: DrawableCache class
				while (type != null) {
					try {
						Method configChangeMethod = type.getDeclaredMethod(
								"onConfigurationChange", Integer.TYPE);
						configChangeMethod.setAccessible(true);
						configChangeMethod.invoke(cache, -1);
						return true;
					} catch (Throwable ignore) {
					}
					type = type.getSuperclass();
				}
			}
		} catch (Throwable ignore) {
			// Not logging these; while there is some checking of SDK_INT here to avoid
			// doing a lot of unnecessary field lookups, it's not entirely accurate and
			// errs on the side of caution (since different devices may have picked up
			// different snapshots of the framework); therefore, it's normal for this
			// to attempt to look up a field for a cache that isn't there; only if it's
			// really there will it continue to flush that particular cache.
		}
		return false;
	}

	public static List<Activity> getActivities(Context context, boolean foregroundOnly)
	{
		List<Activity> list = new ArrayList<Activity>();
		try {
			Class activityThreadClass = Class.forName("android.app.ActivityThread");
			Object activityThread = MultiDexLoader.getActivityThread(context, activityThreadClass);
			Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
			activitiesField.setAccessible(true);
			Collection c;
			Object collection = activitiesField.get(activityThread);
			if (collection instanceof HashMap) {
				// Older platforms
				Map activities = (HashMap) collection;
				c = activities.values();
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
					collection instanceof ArrayMap) {
				ArrayMap activities = (ArrayMap) collection;
				c = activities.values();
			} else {
				return list;
			}
			for (Object activityClientRecord : c) {
				Class activityClientRecordClass = activityClientRecord.getClass();
				if (foregroundOnly) {
					Field pausedField = activityClientRecordClass.getDeclaredField("paused");
					pausedField.setAccessible(true);
					if (pausedField.getBoolean(activityClientRecord)) {
						continue;
					}
				}
				Field activityField = activityClientRecordClass.getDeclaredField("activity");
				activityField.setAccessible(true);
				Activity activity = (Activity) activityField.get(activityClientRecord);
				if (activity != null) {
					list.add(activity);
				}
			}
		} catch (Throwable e) {
			if (Log.isLoggable("MultiDexLoader", Log.WARN)) {
				Log.w("MultiDexLoader", "Error retrieving activities", e);
			}
		}
		return list;
	}
}

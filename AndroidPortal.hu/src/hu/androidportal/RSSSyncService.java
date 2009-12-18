package hu.androidportal;

import hu.androidportal.rss.RSSStream;

import java.net.URL;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * TODO remove 1 minute frequency
 * TODO add menu to the item view activity: share link, preferences, about
 * 
 * FIXME impelment CPU wake lock mechanism
 * @author Gabe
 */
public class RSSSyncService extends Service implements Runnable, Codes
{
	/**
	 * Use this static method to start the service.
	 * 
	 * @param context
	 */
	public static void startService( final Context context, final String action )
	{
		final Intent normalStart = new Intent(action);
		normalStart.setClass(context, RSSSyncService.class);
		context.startService(normalStart);
	}

	public final static int CMD_SHOW_REFRESH = 1;
	public final static int CMD_HIDE_REFRESH = 2;
	public final static int CMD_SHOW_NEWITEM = 3;

	private Handler handler;

	private String feed;
	private URL feedUrl;

	private boolean feedChanged = false;
	private boolean running = false;
	private boolean manualStart;

	@Override
	public void onCreate()
	{
		super.onCreate();
		debugNotification(NOTIFICATION_SERVICE_LIFECYCLE, "Service onCreate() called.");

		updateFeed(null);

		//create handler (to show icons, notifications, read preferences, etc...)
		handler = new Handler()
		{
			@Override
			public void handleMessage( final Message msg )
			{
				super.handleMessage(msg);

				final Context context = getBaseContext();
				final NotificationManager nm = (NotificationManager) context
						.getSystemService(Context.NOTIFICATION_SERVICE);

				if ( msg.what == CMD_SHOW_REFRESH )
				{
					//show refresh icon in notification bar
					Log.d(TAG, "Show refresh icon.");

					//show new icon in the notification bar
					final int icon = android.R.drawable.stat_notify_sync;
					final long when = System.currentTimeMillis();

					final Notification notification = new Notification(icon, "", when);

					final Intent notificationIntent = new Intent(context, ItemListActivity.class);
					notificationIntent.setAction(Intent.ACTION_VIEW);
					notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

					final PendingIntent contentIntent = PendingIntent.getActivity(context, 0, notificationIntent,
							PendingIntent.FLAG_UPDATE_CURRENT);

					notification.setLatestEventInfo(context, "AndroidPortal.hu",
							"Friss�tem az AndroidPortal.hu h�reit...", contentIntent);
					notification.flags |= Notification.FLAG_ONGOING_EVENT;

					//display notification
					nm.notify(NOTIFICATION_REFRESH, notification);
				}
				else if ( msg.what == CMD_HIDE_REFRESH )
				{
					//hide refresh icon in notification bar
					Log.d(TAG, "HIDE refresh icon.");

					nm.cancel(NOTIFICATION_REFRESH);
				}
				else if ( msg.what == CMD_SHOW_NEWITEM )
				{
					Log.d(TAG, "Show NEW ITEM icon.");

					//show new icon in the notification bar
					final int icon = android.R.drawable.stat_notify_chat;
					final long when = System.currentTimeMillis();

					final Notification notification = new Notification(icon,
							"�j cikk �rkezett az AndroidPortal.hu-r�l.", when);

					final Intent notificationIntent = new Intent(context, ItemListActivity.class);
					notificationIntent.setAction(Intent.ACTION_VIEW);
					notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

					final PendingIntent contentIntent = PendingIntent.getActivity(context, 0, notificationIntent,
							PendingIntent.FLAG_UPDATE_CURRENT);

					notification.setLatestEventInfo(context, "AndroidPortal.hu",
							"�j cikk �rkezett az AndroidPortal.hu-r�l.", contentIntent);
					notification.flags |= Notification.FLAG_AUTO_CANCEL;

					//display notification
					nm.notify(NOTIFICATION_NEWITEM, notification);

				}
			}
		};
	}

	/**
	 * Updates the feed url from preferences or from the parameter.
	 * @return True, if the current and the new urls are not the same.
	 */
	private boolean updateFeed( String feed )
	{
		if ( feed == null )
		{
			final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
			feed = preferences.getString(PREF_FEED, DEFAULT_FEED);
		}

		if ( feed.equals(this.feed) )
			return false;

		try
		{
			Log.d(TAG, "Setting feed to: " + feed);
			feedUrl = new URL(feed);
			this.feed = feed;

			return true;
		}
		catch ( final Exception e )
		{
			Log.e(TAG, "Unparseable feed URL:" + feed, e);
			return false;
		}
	}

	@Override
	public void onStart( final Intent intent, final int startId )
	{
		super.onStart(intent, startId);

		debugNotification(NOTIFICATION_SERVICE_LIFECYCLE, "Service onStart() called.");

		boolean start = false;
		if ( intent.getAction().equals(ACTION_SYNCH_NOW) )
		{
			start = true;
		}
		else if ( intent.getAction().equals(ACTION_MANUAL_START) )
		{
			start = manualStart = true;
		}
		else if ( intent.getAction().equals(ACTION_FEED_CHANGED) )
		{
			//start service asap with flag clears database
			start = feedChanged = true;
			updateFeed(intent.getStringExtra(PREF_FEED));
		}

		if ( start && !running )
		{
			clearSchedule(getBaseContext());
			execute();
		}
	}

	/**
	 * Updates the frequency from preferences or from the parameter.
	 * @param newFrequency 
	 * @return True, if the current and the new frequency are not the same.
	 */
	private static long getFrequency( final Context context, final String newFrequency )
	{
		String frequency = newFrequency;
		if ( frequency == null )
		{
			final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
			frequency = preferences.getString(PREF_FREQUENCY, DEFAULT_FREQUENCY);
		}
		final long frequencyInMillis = Long.parseLong(frequency) * 60 * 1000;

		return frequencyInMillis;
	}

	public static void schedule( final Context context, final String newFrequency )
	{
		//FIXME problem may occure if schedule is invoked in the middle of the synch period. Some it should be find out whether real scheduling is required.
		final long frequency = getFrequency(context, newFrequency);
		if ( frequency == 0 )
		{
			Log.w(TAG, "Skip schedule as it is manually scheduled.");
			return;
		}

		final Intent i = new Intent(context, RSSServiceStartReceiver.class);
		i.setAction(ACTION_SYNCH_NOW);
		final PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, 0);

		final AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		final long nextTime = System.currentTimeMillis() + frequency;
		alarm.set(AlarmManager.RTC_WAKEUP, nextTime, pi);
		Log.d(TAG, "Feed synch activated to " + nextTime);
	}

	public static void clearSchedule( final Context context )
	{
		final Intent i = new Intent(context, RSSServiceStartReceiver.class);
		i.setAction(ACTION_SYNCH_NOW);
		final PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, 0);

		final AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		alarm.cancel(pi);
		Log.d(TAG, "Feed synch cleared.");
	}

	@Override
	public void onDestroy()
	{
		super.onDestroy();
		debugNotification(NOTIFICATION_SERVICE_LIFECYCLE, "Service onDestroy() called.");
	}

	@Override
	public IBinder onBind( final Intent intent )
	{
		debugNotification(NOTIFICATION_SERVICE_LIFECYCLE, "Service onBind() called.");
		return null;
	}

	private void execute()
	{
		new Thread(this).start();
	}

	@Override
	public void run()
	{
		running = true;
		try
		{
			//do a refresh
			final boolean backgroundData = ( (ConnectivityManager) getBaseContext().getSystemService(
					Context.CONNECTIVITY_SERVICE) ).getBackgroundDataSetting();

			//manual start and feed changed is not a background process
			if ( backgroundData || manualStart || feedChanged )
				synch(feedChanged);

			feedChanged = false;
			manualStart = false;

			schedule(getBaseContext(), null);

			stopSelf();
		}
		finally
		{
			running = false;
		}
	}

	/**
	 * This method does the actual synch from the stream. It is also handling notifications and 
	 * able to clean up the dB before refresh (this may be necessary, if the feed is changed).
	 * @param cleanUpDB if true, the all cahced item will be deleted before the refresh.
	 */
	private synchronized void synch( final boolean cleanUpDB )
	{
		try
		{
			handler.sendMessage(handler.obtainMessage(CMD_SHOW_REFRESH));
			if ( cleanUpDB )
				RSSStream.deleteItems(getApplicationContext());
			final boolean newItems = RSSStream.synchronize(getBaseContext(), feedUrl);
			if ( newItems
					&& PreferenceManager.getDefaultSharedPreferences(getBaseContext()).getBoolean(PREF_NOTIFICATION,
							DEFAULT_NOTIFICATION) )
			{
				handler.sendMessage(handler.obtainMessage(CMD_SHOW_NEWITEM));
			}

			debugNotification(NOTIFICATION_THREAD_LIFECYCLE, "Synch item found: " + newItems);
		}
		catch ( final Exception e )
		{
			Log.e(TAG, "Unable to synchronise the feed: " + feed, e);
			debugNotification(NOTIFICATION_ERRORS, "Exception: " + e);
		}
		finally
		{
			handler.sendMessage(handler.obtainMessage(CMD_HIDE_REFRESH));
		}
	}

	private void debugNotification( final int level, final String message )
	{
		if ( !PreferenceManager.getDefaultSharedPreferences(getBaseContext()).getBoolean(PREF_DEBUG, DEFAULT_DEBUG) )
			return;

		Log.d(TAG, message);

		final Context context = getApplicationContext();
		//display notification
		final NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

		//create notification
		final int icon = android.R.drawable.stat_sys_warning;
		final long when = System.currentTimeMillis();

		final Notification notification = new Notification(icon, message, when);

		final Intent notificationIntent = new Intent(context, ItemListActivity.class);
		notificationIntent.setAction(Intent.ACTION_VIEW);
		notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

		final PendingIntent contentIntent = PendingIntent.getActivity(context, 0, notificationIntent,
				PendingIntent.FLAG_UPDATE_CURRENT);

		notification.setLatestEventInfo(context, "Debug", message, contentIntent);

		notification.flags |= Notification.FLAG_AUTO_CANCEL;

		//display notification
		nm.notify(level, notification);
	}
}
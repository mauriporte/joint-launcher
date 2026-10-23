package com.mau.hub

import android.service.notification.NotificationListenerService

/** Empty on purpose: being an enabled listener is what lets MainActivity read other apps' media sessions. */
class NowPlayingListener : NotificationListenerService()

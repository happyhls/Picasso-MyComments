/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.picasso;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static com.squareup.picasso.BitmapHunter.forRequest;
import static com.squareup.picasso.Utils.OWNER_DISPATCHER;
import static com.squareup.picasso.Utils.VERB_BATCHED;
import static com.squareup.picasso.Utils.VERB_CANCELED;
import static com.squareup.picasso.Utils.VERB_DELIVERED;
import static com.squareup.picasso.Utils.VERB_ENQUEUED;
import static com.squareup.picasso.Utils.VERB_IGNORED;
import static com.squareup.picasso.Utils.VERB_PAUSED;
import static com.squareup.picasso.Utils.VERB_REPLAYING;
import static com.squareup.picasso.Utils.VERB_RETRYING;
import static com.squareup.picasso.Utils.getLogIdsForHunter;
import static com.squareup.picasso.Utils.getService;
import static com.squareup.picasso.Utils.hasPermission;
import static com.squareup.picasso.Utils.log;

class Dispatcher {
  private static final int RETRY_DELAY = 500;
  private static final int AIRPLANE_MODE_ON = 1;
  private static final int AIRPLANE_MODE_OFF = 0;

  static final int REQUEST_SUBMIT = 1;
  static final int REQUEST_CANCEL = 2;
  static final int REQUEST_GCED = 3;
  static final int HUNTER_COMPLETE = 4;
  static final int HUNTER_RETRY = 5;
  static final int HUNTER_DECODE_FAILED = 6;
  static final int HUNTER_DELAY_NEXT_BATCH = 7;
  static final int HUNTER_BATCH_COMPLETE = 8;
  static final int NETWORK_STATE_CHANGE = 9;
  static final int AIRPLANE_MODE_CHANGE = 10;
  static final int TAG_PAUSE = 11;
  static final int TAG_RESUME = 12;
  static final int REQUEST_BATCH_RESUME = 13;

  private static final String DISPATCHER_THREAD_NAME = "Dispatcher";
  private static final int BATCH_DELAY = 200; // ms

  final DispatcherThread dispatcherThread;
  final Context context;
  final ExecutorService service;
  final Downloader downloader;
  final Map<String, BitmapHunter> hunterMap;
  final Map<Object, Action> failedActions;
  final Map<Object, Action> pausedActions;
  final Set<Object> pausedTags;
  final Handler handler;
  final Handler mainThreadHandler;
  final Cache cache;
  final Stats stats;
  final List<BitmapHunter> batch;
  final NetworkBroadcastReceiver receiver;
  final boolean scansNetworkChanges;

  boolean airplaneMode;

  Dispatcher(Context context, ExecutorService service, Handler mainThreadHandler,
      Downloader downloader, Cache cache, Stats stats) {
    //任务分发线程，继承自ThreadHandler
    this.dispatcherThread = new DispatcherThread();
    this.dispatcherThread.start();
    this.context = context;
    this.service = service;
    //注意这个地方使用了LinkedHashMap，肯定要其特殊的考虑，暂时还不清楚。
    this.hunterMap = new LinkedHashMap<String, BitmapHunter>();
    //此处使用了两个WeakHashMap，也就说，一旦外部放弃对对应的Ojbect的引用的话，该Entry就会在GC时被回收。
    this.failedActions = new WeakHashMap<Object, Action>();
    this.pausedActions = new WeakHashMap<Object, Action>();
    //暂停的任务集合
    this.pausedTags = new HashSet<Object>();
    //这里就是处理分发任务的Handler，查看源代码可以知道，DispatcherHandler工作在dispatcherThread的线程之上。
    this.handler = new DispatcherHandler(dispatcherThread.getLooper(), this);
    this.downloader = downloader;
    //保留主线程的Handler的引用
    this.mainThreadHandler = mainThreadHandler;
    this.cache = cache;
    this.stats = stats;
    //一个列表，用来保存BitmapHunter的batch，其实就是当BitmapHunter执行完成之后，打包在一起，然后等待发送给UI线程进行处理
    //每一个BitmapHunter执行完成之后，最多延时200ms发送结果到主线程中。
    this.batch = new ArrayList<BitmapHunter>(4);
    //是否为飞行模式
    this.airplaneMode = Utils.isAirplaneModeOn(this.context);
    //是否拥有检测网络状态变化的权限
    this.scansNetworkChanges = hasPermission(context, Manifest.permission.ACCESS_NETWORK_STATE);
    //注册网络状态的广播接收
    this.receiver = new NetworkBroadcastReceiver(this);
    receiver.register();
  }

  void shutdown() {
    // Shutdown the thread pool only if it is the one created by Picasso.
    // 关闭Picasso创建的线程池
    if (service instanceof PicassoExecutorService) {
      service.shutdown();
    }
    // 关闭downlaoder
    downloader.shutdown();
    // 退出dispatcherThread
    dispatcherThread.quit();
    // Unregister network broadcast receiver on the main thread.
    // 在主线程上注销receiver，注意和上面的Dispatcher构造函数区分，在构造函数中，没有特别说明需要在主线程上调用receiver.register()，
    Picasso.HANDLER.post(new Runnable() {
      @Override public void run() {
        receiver.unregister();
      }
    });
  }

  void dispatchSubmit(Action action) {
    handler.sendMessage(handler.obtainMessage(REQUEST_SUBMIT, action));
  }

  void dispatchCancel(Action action) {
    handler.sendMessage(handler.obtainMessage(REQUEST_CANCEL, action));
  }

  void dispatchPauseTag(Object tag) {
    handler.sendMessage(handler.obtainMessage(TAG_PAUSE, tag));
  }

  void dispatchResumeTag(Object tag) {
    handler.sendMessage(handler.obtainMessage(TAG_RESUME, tag));
  }

  void dispatchComplete(BitmapHunter hunter) {
    handler.sendMessage(handler.obtainMessage(HUNTER_COMPLETE, hunter));
  }

  void dispatchRetry(BitmapHunter hunter) {
    handler.sendMessageDelayed(handler.obtainMessage(HUNTER_RETRY, hunter), RETRY_DELAY);
  }

  void dispatchFailed(BitmapHunter hunter) {
    handler.sendMessage(handler.obtainMessage(HUNTER_DECODE_FAILED, hunter));
  }

  void dispatchNetworkStateChange(NetworkInfo info) {
    handler.sendMessage(handler.obtainMessage(NETWORK_STATE_CHANGE, info));
  }

  void dispatchAirplaneModeChange(boolean airplaneMode) {
    handler.sendMessage(handler.obtainMessage(AIRPLANE_MODE_CHANGE,
        airplaneMode ? AIRPLANE_MODE_ON : AIRPLANE_MODE_OFF, 0));
  }

  void performSubmit(Action action) {
    performSubmit(action, true);
  }

  //执行提交的action
  void performSubmit(Action action, boolean dismissFailed) {
    //如果pausedTags包含了对应的action
    if (pausedTags.contains(action.getTag())) {
      //那么将action放入到pausedActions当中(我擦，还有暂停功能)
      pausedActions.put(action.getTarget(), action);
      if (action.getPicasso().loggingEnabled) {
        log(OWNER_DISPATCHER, VERB_PAUSED, action.request.logId(),
            "because tag '" + action.getTag() + "' is paused");
      }
      return;
    }

    // 看看该任务是否已经添加过。
    BitmapHunter hunter = hunterMap.get(action.getKey());
    if (hunter != null) {
      hunter.attach(action);
      return;
    }

    //服务终止。。。
    if (service.isShutdown()) {
      if (action.getPicasso().loggingEnabled) {
        log(OWNER_DISPATCHER, VERB_IGNORED, action.request.logId(), "because shut down");
      }
      return;
    }

    //根据任务的uri，找到真正可以执行该任务的对应的Handler，重新封装为一个BitmapHunter
    hunter = forRequest(action.getPicasso(), this, cache, stats, action);
    //提交任务，并且获取一个future
    hunter.future = service.submit(hunter);
    //将提交过的任务放入hunterMap当中
    hunterMap.put(action.getKey(), hunter);
    //忽略失败标志。
    if (dismissFailed) {
      failedActions.remove(action.getTarget());
    }

    if (action.getPicasso().loggingEnabled) {
      log(OWNER_DISPATCHER, VERB_ENQUEUED, action.request.logId());
    }
  }

  void performCancel(Action action) {
    // 获取对应的key
    String key = action.getKey();
    // 从hunterMap中获取对应的BitmapHunter
    BitmapHunter hunter = hunterMap.get(key);
    // 如果hunter存在，则调用hunter.detach(action)和hunter.cancel()删除任务
    if (hunter != null) {
      // 将对应的action从hunter当中删除
      hunter.detach(action);
      // 如果返回true，那么以为这已经没有对应的actions，并且future也通过cancel()函数取消掉了
      if (hunter.cancel()) {
        // 该BitmapHunter不会用到了，删除
        hunterMap.remove(key);
        if (action.getPicasso().loggingEnabled) {
          log(OWNER_DISPATCHER, VERB_CANCELED, action.getRequest().logId());
        }
      }
    }

    // 如果暂停的pasusedTags中还有对应的action
    if (pausedTags.contains(action.getTag())) {
      // 从队列中删除
      pausedActions.remove(action.getTarget());
      if (action.getPicasso().loggingEnabled) {
        log(OWNER_DISPATCHER, VERB_CANCELED, action.getRequest().logId(),
            "because paused request got canceled");
      }
    }

    // 从失败列表中删除对应的action
    Action remove = failedActions.remove(action.getTarget());
    if (remove != null && remove.getPicasso().loggingEnabled) {
      log(OWNER_DISPATCHER, VERB_CANCELED, remove.getRequest().logId(), "from replaying");
    }
  }

  void performPauseTag(Object tag) {
    // Trying to pause a tag that is already paused.
    // 如果返回true，说明添加成功，set已经修改，如果false，说明该tag已经在pasuedTags中，也就是说，任务已经暂停过了。
    if (!pausedTags.add(tag)) {
      return;
    }

    // Go through all active hunters and detach/pause the requests
    // that have the paused tag.
    // 遍历全部正在执行的hunter们，如果BitmapHunter对应的Action有相应的pausedTag，那么就暂定对应的BitmapHunter
    for (Iterator<BitmapHunter> it = hunterMap.values().iterator(); it.hasNext();) {
      BitmapHunter hunter = it.next();
      boolean loggingEnabled = hunter.getPicasso().loggingEnabled;

      // 获取hunter对应的action，已经后来detached的actions。
      Action single = hunter.getAction();
      List<Action> joined = hunter.getActions();
      // 如果joined不为空，并且有元素，那么说明该BitmapHunter拥有多个Action同时附件在上面
      boolean hasMultiple = joined != null && !joined.isEmpty();

      // Hunter has no requests, bail early. 此处说明BitmapHunter没有请求。
      if (single == null && !hasMultiple) {
        continue;
      }

      // 如果下面判断为真，说明需要暂停对应的Action
      if (single != null && single.getTag().equals(tag)) {
        // 从hunter中detach该Action
        hunter.detach(single);
        // 将该Action放入pausedActions队列当中
        pausedActions.put(single.getTarget(), single);
        if (loggingEnabled) {
          log(OWNER_DISPATCHER, VERB_PAUSED, single.request.logId(),
              "because tag '" + tag + "' was paused");
        }
      }

      // 有个地方，我们需要特别强调。此处的tag并不是之前我们说的cacheKey，cacheKey一般是根据图片的网址或者大小而设定的，一般用于缓存等情况
      // 而此处的tag则一般是用来暂定恢复任务，那什么时候要暂定或者恢复任务呢
      // 我进入一个Activity，要使用Picasso显示一个图片，但我希望在Activity退出的时候，该图片只是暂停，因为我后面很快还要用到，
      // 那这个时候就应该使用恢复和暂定，那Tag呢？这种情况下使用Activity的名字作为tag就完全可以。
      
      // 如果BitmapHunter还有附加的其他的Actions，那么还需要依次检查他们。
      if (hasMultiple) {
        for (int i = joined.size() - 1; i >= 0; i--) {
          Action action = joined.get(i);
          if (!action.getTag().equals(tag)) {
            continue;
          }

          hunter.detach(action);
          pausedActions.put(action.getTarget(), action);
          if (loggingEnabled) {
            log(OWNER_DISPATCHER, VERB_PAUSED, action.request.logId(),
                "because tag '" + tag + "' was paused");
          }
        }
      }

      // Check if the hunter can be cancelled in case all its requests
      // had the tag being paused here.
      if (hunter.cancel()) {
        it.remove();
        if (loggingEnabled) {
          log(OWNER_DISPATCHER, VERB_CANCELED, getLogIdsForHunter(hunter), "all actions paused");
        }
      }
    }
  }

  void performResumeTag(Object tag) {
    // Trying to resume a tag that is not paused.
    // 尝试恢复tag对应的暂停的请求
    if (!pausedTags.remove(tag)) {
      return;
    }

    // 将同一个tag对应的Action放在一个List当中
    List<Action> batch = null;
    for (Iterator<Action> i = pausedActions.values().iterator(); i.hasNext();) {
      Action action = i.next();
      if (action.getTag().equals(tag)) {
        if (batch == null) {
          batch = new ArrayList<Action>();
        }
        batch.add(action);
        i.remove();
      }
    }

    if (batch != null) {
      // 通知主线程进行处理
      // 在主线程上做的工作是，尝试从Cache中获取bitmap，如果成功，则直接分发，不成功，就重新将action加入到队列并提交
      mainThreadHandler.sendMessage(mainThreadHandler.obtainMessage(REQUEST_BATCH_RESUME, batch));
    }
  }

  void performRetry(BitmapHunter hunter) {
    if (hunter.isCancelled()) return;

    if (service.isShutdown()) {
      performError(hunter, false);
      return;
    }

    // 检查网络状态
    NetworkInfo networkInfo = null;
    if (scansNetworkChanges) {
      ConnectivityManager connectivityManager = getService(context, CONNECTIVITY_SERVICE);
      networkInfo = connectivityManager.getActiveNetworkInfo();
    }

    // 是否有网络连接
    boolean hasConnectivity = networkInfo != null && networkInfo.isConnected();
    // 根据当前的网络状态和BitmapHunter的重试次数，来判断是否需要重试
    boolean shouldRetryHunter = hunter.shouldRetry(airplaneMode, networkInfo);
    // BitmapHunter中保存的Action对应的RequestHandler是否支持重试？
    boolean supportsReplay = hunter.supportsReplay();

    if (!shouldRetryHunter) {
      // Mark for replay only if we observe network info changes and support replay.
      // 如果BitmapHunter的重试次数已经用完，还是要根据当前的网络状态看看是否还让其重新尝试
      boolean willReplay = scansNetworkChanges && supportsReplay;
      // 通知执行performError
      performError(hunter, willReplay);
      // 如果网络状态变化，并且RequestHandler支持重试，那么就重新尝试
      if (willReplay) {
        // 标记以重新尝试 ，调用markForReplay(hunter)，实际上是将对应的Action放入到failedActions当中。
        markForReplay(hunter);
      }
      return;
    }

    // If we don't scan for network changes (missing permission) or if we have connectivity, retry.
    // 此时对于BitmapHunter来说，支持网络重试，如果受权限限制无法检查网络状态，或者是网络连接，那么直接重试。
    if (!scansNetworkChanges || hasConnectivity) {
      if (hunter.getPicasso().loggingEnabled) {
        log(OWNER_DISPATCHER, VERB_RETRYING, getLogIdsForHunter(hunter));
      }
      // 向service提交该hunter。
      hunter.future = service.submit(hunter);
      return;
    }

    performError(hunter, supportsReplay);

    // 如果支持重试，调用markForReplay(hunter)，实际上是将对应的Action放入到failedActions当中。
    if (supportsReplay) {
      markForReplay(hunter);
    }
  }

  void performComplete(BitmapHunter hunter) {
    // 是否要跳过Memory Cache
    if (!hunter.shouldSkipMemoryCache()) {
      // 不跳过的话，则将hunter的key和结果放入Cache当中Result为对应的图像
      cache.set(hunter.getKey(), hunter.getResult());
    }
    // 从hunterMap中删除该hunter
    hunterMap.remove(hunter.getKey());
    // 打包，看说明是将hunter放到batch的list当中，并检查如果没有HUNTER_DELAY_NEXT_BATCH，则200ms之后，发送该Message
    // handler处理该Message，其实实际上直接调用performBatchComplete()
    batch(hunter);
    if (hunter.getPicasso().loggingEnabled) {
      log(OWNER_DISPATCHER, VERB_BATCHED, getLogIdsForHunter(hunter), "for completion");
    }
  }

  void performBatchComplete() {
    // 打包处理，同时取出batch所有的BitmapHunter，并发送给主线程Handler进行处理。
    List<BitmapHunter> copy = new ArrayList<BitmapHunter>(batch);
    batch.clear();
    mainThreadHandler.sendMessage(mainThreadHandler.obtainMessage(HUNTER_BATCH_COMPLETE, copy));
    logBatch(copy);
  }

  void performError(BitmapHunter hunter, boolean willReplay) {
    if (hunter.getPicasso().loggingEnabled) {
      log(OWNER_DISPATCHER, VERB_BATCHED, getLogIdsForHunter(hunter),
          "for error" + (willReplay ? " (will replay)" : ""));
    }
    hunterMap.remove(hunter.getKey());
    batch(hunter);
  }

  void performAirplaneModeChange(boolean airplaneMode) {
    this.airplaneMode = airplaneMode;
  }

  void performNetworkStateChange(NetworkInfo info) {
    if (service instanceof PicassoExecutorService) {
      ((PicassoExecutorService) service).adjustThreadCount(info);
    }
    // Intentionally check only if isConnected() here before we flush out failed actions.
    if (info != null && info.isConnected()) {
      flushFailedActions();
    }
  }

  private void flushFailedActions() {
    if (!failedActions.isEmpty()) {
      Iterator<Action> iterator = failedActions.values().iterator();
      while (iterator.hasNext()) {
        Action action = iterator.next();
        iterator.remove();
        if (action.getPicasso().loggingEnabled) {
          log(OWNER_DISPATCHER, VERB_REPLAYING, action.getRequest().logId());
        }
        performSubmit(action, false);
      }
    }
  }

  // 该函数所起的作用实际上是标记BitmapHunter对应的Action中的willReplay标志，并将其放在failedActions当中
  private void markForReplay(BitmapHunter hunter) {
    Action action = hunter.getAction();
    if (action != null) {
      markForReplay(action);
    }
    List<Action> joined = hunter.getActions();
    if (joined != null) {
      //noinspection ForLoopReplaceableByForEach
      for (int i = 0, n = joined.size(); i < n; i++) {
        Action join = joined.get(i);
        markForReplay(join);
      }
    }
  }

  private void markForReplay(Action action) {
    Object target = action.getTarget();
    if (target != null) {
      action.willReplay = true;
      failedActions.put(target, action);
    }
  }

  private void batch(BitmapHunter hunter) {
    // 如果hunter已经取消的话，则直接返回
    if (hunter.isCancelled()) {
      return;
    }
    // 放到List当中
    batch.add(hunter);
    // 如果没有消息HUNTER_DELAY_NEXT_BATCH正在执行，那么就发送请求，其实最后还是调用的performBatchComplete
    if (!handler.hasMessages(HUNTER_DELAY_NEXT_BATCH)) {
      handler.sendEmptyMessageDelayed(HUNTER_DELAY_NEXT_BATCH, BATCH_DELAY);
    }
  }

  private void logBatch(List<BitmapHunter> copy) {
    if (copy == null || copy.isEmpty()) return;
    BitmapHunter hunter = copy.get(0);
    Picasso picasso = hunter.getPicasso();
    if (picasso.loggingEnabled) {
      StringBuilder builder = new StringBuilder();
      for (BitmapHunter bitmapHunter : copy) {
        if (builder.length() > 0) builder.append(", ");
        builder.append(Utils.getLogIdsForHunter(bitmapHunter));
      }
      log(OWNER_DISPATCHER, VERB_DELIVERED, builder.toString());
    }
  }

  //Dispacher的处理函数
  private static class DispatcherHandler extends Handler {
    private final Dispatcher dispatcher;

    // 根据Dispatcher中调用的构造函数DispatcherHandler(dispatcherThread.getLooper(), this)可以看出，
    // 该Handler其实是工作在DispatcherThread之上的。
    public DispatcherHandler(Looper looper, Dispatcher dispatcher) {
      super(looper);
      this.dispatcher = dispatcher;
    }

    // 根据不同的Message，调用dispatcher中对应的不同的方法。
    @Override public void handleMessage(final Message msg) {
      switch (msg.what) {
        case REQUEST_SUBMIT: {
          //Action提交之后，会调用dispatcher.performSubmit(action)函数。
          Action action = (Action) msg.obj;
          dispatcher.performSubmit(action);
          break;
        }
        case REQUEST_CANCEL: {
          Action action = (Action) msg.obj;
          dispatcher.performCancel(action);
          break;
        }
        case TAG_PAUSE: {
          Object tag = msg.obj;
          dispatcher.performPauseTag(tag);
          break;
        }
        case TAG_RESUME: {
          Object tag = msg.obj;
          dispatcher.performResumeTag(tag);
          break;
        }
        case HUNTER_COMPLETE: {
          // BitmapHunter执行完成之后，则发送给performComplete函数进行处理，其实是打包，然后一起处理
          BitmapHunter hunter = (BitmapHunter) msg.obj;
          dispatcher.performComplete(hunter);
          break;
        }
        case HUNTER_RETRY: {
          BitmapHunter hunter = (BitmapHunter) msg.obj;
          dispatcher.performRetry(hunter);
          break;
        }
        case HUNTER_DECODE_FAILED: {
          BitmapHunter hunter = (BitmapHunter) msg.obj;
          dispatcher.performError(hunter, false);
          break;
        }
        case HUNTER_DELAY_NEXT_BATCH: {
          // 最后调用到performBatchComplete
          dispatcher.performBatchComplete();
          break;
        }
        case NETWORK_STATE_CHANGE: {
          NetworkInfo info = (NetworkInfo) msg.obj;
          dispatcher.performNetworkStateChange(info);
          break;
        }
        case AIRPLANE_MODE_CHANGE: {
          dispatcher.performAirplaneModeChange(msg.arg1 == AIRPLANE_MODE_ON);
          break;
        }
        default:
          Picasso.HANDLER.post(new Runnable() {
            @Override public void run() {
              throw new AssertionError("Unknown handler message received: " + msg.what);
            }
          });
      }
    }
  }

  static class DispatcherThread extends HandlerThread {
    DispatcherThread() {
      super(Utils.THREAD_PREFIX + DISPATCHER_THREAD_NAME, THREAD_PRIORITY_BACKGROUND);
    }
  }

  // 网络状态变化广播接收器
  static class NetworkBroadcastReceiver extends BroadcastReceiver {
    static final String EXTRA_AIRPLANE_STATE = "state";

    //保存对Dispatcher线程的引用
    private final Dispatcher dispatcher;

    NetworkBroadcastReceiver(Dispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }

    // 注册广播接收器
    void register() {
      // 设置IntentFilter
      IntentFilter filter = new IntentFilter();
      filter.addAction(ACTION_AIRPLANE_MODE_CHANGED);
      if (dispatcher.scansNetworkChanges) {
        filter.addAction(CONNECTIVITY_ACTION);
      }
      // 注册广播接收器
      dispatcher.context.registerReceiver(this, filter);
    }

    void unregister() {
      // 取消注册
      dispatcher.context.unregisterReceiver(this);
    }

    @Override public void onReceive(Context context, Intent intent) {
      // On some versions of Android this may be called with a null Intent,
      // also without extras (getExtras() == null), in such case we use defaults.
      if (intent == null) {
        return;
      }
      final String action = intent.getAction();
      if (ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
        if (!intent.hasExtra(EXTRA_AIRPLANE_STATE)) {
          return; // No airplane state, ignore it. Should we query Utils.isAirplaneModeOn?
        }
        dispatcher.dispatchAirplaneModeChange(intent.getBooleanExtra(EXTRA_AIRPLANE_STATE, false));
      } else if (CONNECTIVITY_ACTION.equals(action)) {
        ConnectivityManager connectivityManager = getService(context, CONNECTIVITY_SERVICE);
        dispatcher.dispatchNetworkStateChange(connectivityManager.getActiveNetworkInfo());
      }
    }
  }
}

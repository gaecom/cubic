package com.cubic.agent.cmd.jvm.thread;

import com.cubic.agent.boot.CommonService;
import com.cubic.agent.boot.DefaultService;
import com.cubic.agent.boot.ServiceManager;
import com.cubic.agent.conf.AgentConfig;
import com.cubic.agent.remote.*;
import com.cubic.proxy.common.module.Message;
import com.cubic.proxy.common.module.ThreadMetricCollection;
import com.cubic.proxy.common.thread.RunnableWithExceptionProtection;
import com.google.gson.Gson;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * JVM线程栈信息收集发送处理
 *
 * @Author qinqixuan
 * @Date 2020/12/08
 * @Version V1.0
 **/
@DefaultService
public class JvmThreadService implements CommonService {

    private static final Logger logger = LoggerFactory.getLogger(JvmThreadService.class);

    private volatile ScheduledFuture<?> sendMetricFuture;
    private Sender sender;
    private volatile AgentNettyClient client;

    @Override
    public void prepare() {
        sender = new Sender();
        ServiceManager.INSTANCE.findService(AgentClientManager.class).addListener(sender);
    }

    @Override
    public void start() {
        sendMetricFuture = new ScheduledThreadPoolExecutor(1, new DefaultThreadFactory("JVMService-consume"))
                .scheduleAtFixedRate(new RunnableWithExceptionProtection(sender, th -> {
            logger.error("JVMService consumes and upload failure.", th);
        }), 0, 1, TimeUnit.MINUTES);
    }

    @Override
    public void shutdown() {
        sendMetricFuture.cancel(true);
    }

    @Override
    public void complete() {

    }

    class Sender implements Runnable, AgentChannelListener {
        private volatile ChannelStatus status = ChannelStatus.DISCONNECT;

        @Override
        public void run() {
            if (status == ChannelStatus.CONNECTION) {
                try {
                    long currentTimeMillis = System.currentTimeMillis();
                    ThreadMetricCollection.Builder builder = new ThreadMetricCollection.Builder();
                    builder.setThreadDump(ThreadProvider.INSTANCE.getThreadDump());
                    builder.setAllThreadPools(ThreadPoolProvider.INSTANCE.getDubboThreadPool());
                    builder.setServiceName(AgentConfig.Agent.SERVICE_NAME);
                    builder.setTime(currentTimeMillis);
                    builder.setInstanceUUID(AgentConfig.Agent.INSTANCE_UUID);
                    Gson gson = new Gson();
                    Message message = builder.build(CommandCode.JVM_THREAD_DUMP.getCode(), "jvm thread dump", AgentConfig.Agent.INSTANCE_UUID);
                    client.getChannel().writeAndFlush(gson.toJson(message)).addListener((ChannelFutureListener) future -> {
                        if (future.isSuccess()) {
                            logger.debug("JvmThreadService send successful");
                        } else {
                            logger.error("JvmThreadService send {} fail", AgentConfig.Agent.INSTANCE_UUID);
                        }
                    });
                } catch (Throwable t) {
                    logger.error("send JVM thread metrics to Collector fail.", t);
                }
            }
        }

        @Override
        public void statusChanged(ChannelStatus status) {
            if (ChannelStatus.CONNECTION.equals(status)) {
                client = ServiceManager.INSTANCE.findService(AgentClientManager.class).getClient();
            }
            this.status = status;
        }
    }
}

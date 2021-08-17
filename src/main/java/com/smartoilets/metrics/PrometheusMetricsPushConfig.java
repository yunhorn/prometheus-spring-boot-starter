package com.smartoilets.metrics;

import com.google.common.collect.Lists;
import com.smartoilets.common.util.JSONUtils;
import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.xerial.snappy.Snappy;
import prometheus.Remote;
import prometheus.Types;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class PrometheusMetricsPushConfig {

    @Value("${management.metrics.export.prometheus.push.host:}")
    String pushHost;

    @Value("${management.metrics.export.prometheus.push.path:}")
    String pushPath;

    @Value("${prometheus.pushgateway.intervalInMillis:10000}")
    long intervalInMillis;

    @Autowired
    MeterRegistry meterRegistry;

    RestTemplate restTemplate = new RestTemplate();

    public void beginPush(Instant instant,String metricsName,List<Double> values,List<List<Tag>> tagList){
        if(values.isEmpty()){
            return;
        }
        //TODO may BUG, send same data twice
        log.debug("***begin.push:{}|{}|{}",metricsName,values.size(), tagList.hashCode());

        String name = metricsName.replaceAll("\\.","_");

        Types.Label nameLabel = Types.Label.newBuilder()
                .setName("__name__")
                .setValue(name)
                .build();

        Remote.WriteRequest.Builder builder = Remote.WriteRequest.newBuilder();

        for (int i = 0; i < values.size(); i++) {
            List<Tag> tags = tagList.get(i);
            List<Types.Label> labels = Lists.newArrayList();
            tags.forEach(tag->{
                Types.Label label = Types.Label.newBuilder()
                        .setName(tag.getKey())
                        .setValue(tag.getValue())
                        .build();
                labels.add(label);
            });
            labels.add(nameLabel);

           builder.addTimeseries(
                    Types.TimeSeries.newBuilder()
                            .addAllLabels(labels)
                            .addSamples(Types.Sample.newBuilder()
                                    .setTimestamp(instant.toEpochMilli())
                                    .setValue(values.get(i)).build())
                            .build()
            );
        }
        Remote.WriteRequest build = builder.build();

        try {
            byte[] compress = Snappy.compress(build.toByteArray());
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<?> entity = new HttpEntity<>(compress, headers);
//            log.debug("begin write!!"+name+" "+value);
            ResponseEntity<String> responseEntity = restTemplate.exchange(pushHost+pushPath, HttpMethod.POST, entity, String.class);
            log.debug("resp:"+name+" "+instant.atZone(ZoneOffset.ofHours(8))+" "+responseEntity.getStatusCodeValue());
        } catch (Exception e) {
            log.error("metrics.push.failed!"+name,e);
        }
    }

    public void beginPush(Instant instant,String metricsName,double value,List<Tag> tagList){

        List<Types.Label> labels = Lists.newArrayList();
        tagList.forEach(tag->{
            Types.Label label = Types.Label.newBuilder()
                    .setName(tag.getKey())
                    .setValue(tag.getValue())
                    .build();
            labels.add(label);
        });
        String name = metricsName.replaceAll("\\.","_");

        Types.Label nameLabel = Types.Label.newBuilder()
                .setName("__name__")
                .setValue(name)
                .build();
        labels.add(nameLabel);
        Remote.WriteRequest build = Remote.WriteRequest.newBuilder()
                .addTimeseries(Types.TimeSeries.newBuilder()
                        .addAllLabels(labels)
                        .addSamples(Types.Sample.newBuilder()
                                .setTimestamp(instant.toEpochMilli())
                                .setValue(Double.valueOf(value).floatValue())
                                .build())
                        .build())
                .build();

        try {
            byte[] compress = Snappy.compress(build.toByteArray());
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<?> entity = new HttpEntity<>(compress, headers);
//            log.debug("begin write!!"+name+" "+value);
            ResponseEntity<String> responseEntity = restTemplate.exchange(pushHost+pushPath, HttpMethod.POST, entity, String.class);
            log.debug("resp:"+name+" "+instant.atZone(ZoneOffset.ofHours(8))+" "+responseEntity.getStatusCodeValue());
        } catch (Exception e) {
            log.error("metrics.push.failed!"+name+"|"+value,e);
        }
    }

    @PostConstruct
    public void initialize() {
        if(StringUtils.isBlank(pushHost)){
            log.warn("not.prometheus.push.host");
            return;
        }
        log.info("initialize");

        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduledExecutorService.shutdown();
        }));

        scheduledExecutorService.scheduleAtFixedRate(() -> {
            Instant instant = LocalDateTime.now().toInstant(ZoneOffset.of("+8"));
//            log.info("metrics.size:"+meterRegistry.getMeters().size());
            meterRegistry.getMeters().stream().forEach(m->{
                Meter.Id id = m.getId();
                String name = id.getName().replaceAll("\\.","_");
//                log.info("begin metrics!!"+name);
//                log.info("type:"+id.getType());
                Optional<Counter> counter = Optional.ofNullable(meterRegistry.find(id.getName()).counter());
                Collection<Gauge> gauges = meterRegistry.find(id.getName()).gauges();
                List<Double> values = Lists.newArrayList();
                List<List<Tag>> tags = Lists.newArrayList();
                gauges.forEach(g->{
                    values.add(g.value());
                    tags.add(g.getId().getTags());
//                    beginPush(instant,id.getName(),g.value(),g.getId().getTags());
//                    log.info("gaug.data:{}|{}|{}",id.getName(),g.getId().getTags(),g.value());
                });
                beginPush(instant,id.getName(),values,tags);
//                log.info("gauges.size:{}",gauges.size());
                if(!counter.isPresent()){
                    return;
                }
                beginPush(instant,id.getName(),counter.get().count(),id.getTags());
            });
        }, 5000, intervalInMillis, TimeUnit.MILLISECONDS);
    }


}

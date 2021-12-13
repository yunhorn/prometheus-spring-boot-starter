#  这个starter主要是实现主动推送metrics到prometheus  

当然,任何支持prometheus remote write的后端存储都可以使用,将metrics收集的pull方式使用push方式来做.

如果是使用prometheus存储的话,需要版本至少为v2.25.0.

# prometheus-spring-boot-starter
push metrics to prometheus

Note: Since prometheus v2.25.0 support remote_write.So promethues version `>=v2.25.0`. see [https://github.com/prometheus/prometheus/pull/8424](https://github.com/prometheus/prometheus/pull/8424)
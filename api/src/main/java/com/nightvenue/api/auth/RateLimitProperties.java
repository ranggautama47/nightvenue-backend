package com.nightvenue.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("nightvenue.ratelimit")
public class RateLimitProperties {
    private Limit login = new Limit(5, Duration.ofMinutes(1));
    private Limit refresh = new Limit(10, Duration.ofMinutes(1));
    private Limit logout = new Limit(20, Duration.ofMinutes(1));
    private Limit me = new Limit(30, Duration.ofMinutes(1));

    public Limit getLogin() {
        return login;
    }

    public void setLogin(Limit login) {
        this.login = login;
    }

    public Limit getRefresh() {
        return refresh;
    }

    public void setRefresh(Limit refresh) {
        this.refresh = refresh;
    }

    public Limit getLogout() {
        return logout;
    }

    public void setLogout(Limit logout) {
        this.logout = logout;
    }

    public Limit getMe() {
        return me;
    }

    public void setMe(Limit me) {
        this.me = me;
    }

    public static class Limit {
        private int capacity;
        private Duration period;

        public Limit() {
        }

        public Limit(int capacity, Duration period) {
            this.capacity = capacity;
            this.period = period;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public Duration getPeriod() {
            return period;
        }

        public void setPeriod(Duration period) {
            this.period = period;
        }
    }
}

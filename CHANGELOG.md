# CHANGELOG

Please enumerate **all user-facing** changes using format `<githib issue/pr number>: <short description>`, with changes ordered in reverse chronological order.

## 1.0.0

## 0.3.0

* [#412](https://github.com/kroxylicious/kroxylicious/issues/412): Remove $(portNumber) pattern from brokerAddressPattern for SniRouting and PortPerBroker schemes
* [#414](https://github.com/kroxylicious/kroxylicious/pull/414): Add kubernetes sample illustrating SNI based routing, downstream/upstream TLS and the use of certificates from cert-manager.
* [#392](https://github.com/kroxylicious/kroxylicious/pull/392): Introduce CompositeFilters
* [#401](https://github.com/kroxylicious/kroxylicious/pull/401): Fix netty buffer leak when doing a short-circuit response
* [#409](https://github.com/kroxylicious/kroxylicious/pull/409): Bump netty.version from 4.1.93.Final to 4.1.94.Final #409 
* [#374](https://github.com/kroxylicious/kroxylicious/issues/374) Upstream TLS support
* [#375](https://github.com/kroxylicious/kroxylicious/issues/375) Support key material in PEM format (X.509 certificates and PKCS-8 private keys) 
* [#398](https://github.com/kroxylicious/kroxylicious/pull/398): Validate admin port does not collide with cluster ports
* [#384](https://github.com/kroxylicious/kroxylicious/pull/384): Bump guava from 32.0.0-jre to 32.0.1-jre
* [#372](https://github.com/kroxylicious/kroxylicious/issues/372): Eliminate the test config model from the code-base
* [#364](https://github.com/kroxylicious/kroxylicious/pull/364): Add Dockerfile for kroxylicious

### Changes, deprecations and removals

The default metrics port has changed from 9193 to 9190 to prevent port collisions

Filter Authors can now implement CompositeFilter if they want a single configuration block to contribute multiple Filters
to the Filter chain. This enables them to write smaller, more focused Filter implementations but deliver them as a whole
behaviour with a single block of configuration in the Kroxylicious configuration yaml. This interface is mutually exclusive
with RequestFilter, ResponseFilter or any specific message Filter interfaces.

In the kroxylicious config, the brokerAddressPattern parameter for the PortPerBroker scheme no longer accepts or requires
:$(portNumber) suffix.  In addition, for the SniRouting scheme the config now enforces that there is no port specifier
present on the brokerAddressPattern parameter. Previously, it was accepted but would lead to a failure later.


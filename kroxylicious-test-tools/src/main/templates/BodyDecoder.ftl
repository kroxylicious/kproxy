<#--

    Copyright Kroxylicious Authors.

    Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0

-->
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ${outputPackage};

<#list messageSpecs as messageSpec>
import org.apache.kafka.common.message.${messageSpec.name}Data;
</#list>
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.Readable;

/**
* Decodes Kafka Readable into an ApiMessage
* <p>Note: this class is automatically generated from a template</p>
*/
public class BodyDecoder {

    /**
    * Creates a BodyDecoder
    */
    private BodyDecoder() {
    }

    /**
    * Decodes Kafka request Readable into an ApiMessage
    * @param apiKey the api key of the message
    * @param apiVersion the api version of the message
    * @param accessor the accessor for the message bytes
    * @return the ApiMessage
    * @throws IllegalArgumentException if an unhandled ApiKey is encountered
    */
    static ApiMessage decodeRequest(ApiKeys apiKey, short apiVersion, Readable accessor) {
        switch (apiKey) {
<#list messageSpecs as messageSpec>
    <#if messageSpec.type?lower_case == 'request'>
            case ${retrieveApiKey(messageSpec)}:
                return new ${messageSpec.name}Data(accessor, apiVersion);
    </#if>
</#list>
            default:
                throw new IllegalArgumentException("Unsupported RPC " + apiKey);
        }
    }

    /**
    * Decodes Kafka response Readable into an ApiMessage
    * @param apiKey the api key of the message
    * @param apiVersion the api version of the message
    * @param accessor the accessor for the message bytes
    * @return the ApiMessage
    * @throws IllegalArgumentException if an unhandled ApiKey is encountered
    */
    static ApiMessage decodeResponse(ApiKeys apiKey, short apiVersion, Readable accessor) {
        switch (apiKey) {
<#list messageSpecs as messageSpec>
    <#if messageSpec.type?lower_case == 'response'>
            case ${retrieveApiKey(messageSpec)}:
                return new ${messageSpec.name}Data(accessor, apiVersion);
    </#if>
</#list>
            default:
                throw new IllegalArgumentException("Unsupported RPC " + apiKey);
        }
    }

}

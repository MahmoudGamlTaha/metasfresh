/*
 *
 * * #%L
 * * %%
 * * Copyright (C) <current year> metas GmbH
 * * %%
 * * This program is free software: you can redistribute it and/or modify
 * * it under the terms of the GNU General Public License as
 * * published by the Free Software Foundation, either version 2 of the
 * * License, or (at your option) any later version.
 * *
 * * This program is distributed in the hope that it will be useful,
 * * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * * GNU General Public License for more details.
 * *
 * * You should have received a copy of the GNU General Public
 * * License along with this program. If not, see
 * * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * * #L%
 *
 */

package de.metas.vertical.pharma.securpharm;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import javax.net.ssl.SSLContext;

import org.adempiere.exceptions.AdempiereException;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import de.metas.logging.LogManager;
import de.metas.vertical.pharma.securpharm.model.DecommissionAction;
import de.metas.vertical.pharma.securpharm.model.ProductCodeType;
import de.metas.vertical.pharma.securpharm.model.ProductData;
import de.metas.vertical.pharma.securpharm.model.SecurPharmActionResult;
import de.metas.vertical.pharma.securpharm.model.SecurPharmConfig;
import de.metas.vertical.pharma.securpharm.model.SecurPharmProductDataResult;
import de.metas.vertical.pharma.securpharm.model.SecurPharmRequestLogData;
import de.metas.vertical.pharma.securpharm.model.schema.APIResponse;
import de.metas.vertical.pharma.securpharm.model.schema.AuthResponse;
import de.metas.vertical.pharma.securpharm.model.schema.State;
import lombok.Getter;
import lombok.NonNull;

public class SecurPharmClient
{
	public static SecurPharmClient createAndAuthenticate(@NonNull final SecurPharmConfig config)
	{
		try
		{
			final SecurPharmClient client = new SecurPharmClient(config);
			client.getAccessToken(); // make sure it's authenticated
			return client;
		}
		catch (final AdempiereException ex)
		{
			throw ex;
		}
		catch (final Exception ex)
		{
			throw new AdempiereException(SecurPharmConstants.AUTHORIZATION_FAILED_MESSAGE, AdempiereException.extractCause(ex));
		}
	}

	private static final Logger logger = LogManager.getLogger(SecurPharmClient.class);

	@Getter
	private final SecurPharmConfig config;
	private final RestTemplate apiRestTemplate;
	private final ObjectMapper jsonObjectMapper = new ObjectMapper();

	private AuthResponse _authResponse; // lazy

	private SecurPharmClient(@NonNull final SecurPharmConfig config)
	{
		this.config = config;

		apiRestTemplate = new RestTemplateBuilder()
				.rootUri(config.getPharmaAPIBaseUrl())
				.build();
	}

	public SecurPharmProductDataResult decodeDataMatrix(@NonNull final String dataMatrix)
	{
		final String dataMatrixBase64Encoded = Base64.getEncoder().encodeToString(dataMatrix.getBytes());
		final String clientTrx = UUID.randomUUID().toString();
		final UriComponentsBuilder url = UriComponentsBuilder.fromPath(SecurPharmConstants.API_RELATIVE_PATH_UIS)
				.path(dataMatrixBase64Encoded)
				.queryParam(SecurPharmConstants.QUERY_PARAM_CTX, clientTrx)
				.queryParam(SecurPharmConstants.QUERY_PARAM_SID, config.getApplicationUUID());

		final SecurPharmRequestLogData.SecurPharmRequestLogDataBuilder logDataBuilder = SecurPharmRequestLogData.builder()
				.requestTime(LocalDateTime.now())
				.clientTransactionID(clientTrx)
				.requestUrl(config.getPharmaAPIBaseUrl() + url.build());
		final SecurPharmProductDataResult securPharmProductDataResult = new SecurPharmProductDataResult();

		try
		{
			final ResponseEntity<String> response = performRequest(url, HttpMethod.GET);
			final APIResponse apiResponse = fromJsonString(response.getBody(), APIResponse.class);
			logDataBuilder.responseTime(LocalDateTime.now())
					.responseData(toJsonString(apiResponse));
			securPharmProductDataResult.setRequestLogData(logDataBuilder.build());
			if (response.getStatusCode() == HttpStatus.OK
					&& apiResponse.getProd() != null
					&& apiResponse.getPack() != null
					&& apiResponse.getTx() != null)
			{
				logDataBuilder.serverTransactionID(apiResponse.getTx().getServerTransactionId());
				final byte[] decodedSerialNumber = Base64.getDecoder().decode(apiResponse.getPack().getSerialNumber());
				final byte[] decodedLot = Base64.getDecoder().decode(apiResponse.getProd().getLot());
				final ProductData.ProductDataBuilder productDataBuilder = ProductData.builder()
						.lot(new String(decodedLot))
						.productCode(apiResponse.getProd().getProductCode())
						// TODO check from where to set this (response?)
						.productCodeType(ProductCodeType.GTIN)
						.expirationDate(apiResponse.getProd().getExpirationDate().toLocalDate())
						.serialNumber(new String(decodedSerialNumber));
				if (apiResponse.getPack().getState() == State.ACTIVE)
				{
					productDataBuilder.active(true);
				}
				else
				{
					productDataBuilder.active(false);
					if (apiResponse.getPack().getReasons() != null)
					{
						productDataBuilder.inactiveReason(String.join(SecurPharmConstants.DELIMITER, apiResponse.getPack().getReasons()));
					}
				}
				securPharmProductDataResult.setError(false);
				securPharmProductDataResult.setProductData(productDataBuilder.build());
			}
			else
			{
				// should not get here
				securPharmProductDataResult.setError(true);
			}
		}
		catch (final HttpClientErrorException e)
		{
			logger.error("HTTP error", e);
			securPharmProductDataResult.setError(true);
			logDataBuilder.responseTime(LocalDateTime.now())
					.responseData(e.getStatusCode() + e.getResponseBodyAsString());
			securPharmProductDataResult.setRequestLogData(logDataBuilder.build());
		}

		return securPharmProductDataResult;
	}

	private String toJsonString(final Object obj)
	{
		try
		{
			return jsonObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
		}
		catch (final JsonProcessingException e)
		{
			throw new AdempiereException("Failed converting object to JSON string: " + obj, e);
		}
	}

	private <T> T fromJsonString(final String json, final Class<T> type)
	{
		try
		{
			return jsonObjectMapper.readValue(json, type);
		}
		catch (final Exception e)
		{
			throw new AdempiereException("Failed converting JSON to " + type.getSimpleName(), e)
					.appendParametersToMessage()
					.setParameter("json", json);
		}
	}

	public SecurPharmActionResult decommission(
			@NonNull final ProductData productData,
			@NonNull final DecommissionAction action)
	{
		final UriComponentsBuilder uri = getDecommisionPathBuilder(productData, action);
		return getSecurPharmActionResult(action, uri);
	}

	private SecurPharmActionResult getSecurPharmActionResult(
			@NonNull final DecommissionAction action,
			@NonNull final UriComponentsBuilder builder)
	{
		final SecurPharmRequestLogData.SecurPharmRequestLogDataBuilder logDataBuilder = SecurPharmRequestLogData.builder()
				.requestTime(LocalDateTime.now())
				.requestUrl(config.getPharmaAPIBaseUrl() + builder.build());
		final SecurPharmActionResult result = new SecurPharmActionResult();
		result.setAction(action);
		try
		{
			final ResponseEntity<String> response = performRequest(builder, HttpMethod.PUT);
			final APIResponse apiResponse = fromJsonString(response.getBody(), APIResponse.class);
			logDataBuilder.responseData(toJsonString(apiResponse));
			if (response.getStatusCode() == HttpStatus.OK && apiResponse.getTx() != null)
			{
				result.setError(false);
				logDataBuilder.serverTransactionID(apiResponse.getTx().getServerTransactionId());
			}
			else
			{
				// should not get here
				result.setError(true);
			}
		}
		catch (final HttpClientErrorException e)
		{
			logger.error("HTTP error", e);

			result.setError(true);
			logDataBuilder.responseTime(LocalDateTime.now())
					.responseData(e.getStatusCode() + e.getResponseBodyAsString());
			result.setRequestLogData(logDataBuilder.build());
		}
		return result;
	}

	public SecurPharmActionResult undoDecommission(@NonNull final ProductData productData, @NonNull final DecommissionAction action, @NonNull final String trx)
	{
		final UriComponentsBuilder builder = getDecommisionPathBuilder(productData, action)
				.queryParam(SecurPharmConstants.QUERY_PARAM_TRX, trx);

		return getSecurPharmActionResult(action, builder);
	}

	private ResponseEntity<String> performRequest(final UriComponentsBuilder uri, final HttpMethod method)
	{
		final HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setAccept(ImmutableList.of(MediaType.APPLICATION_JSON));
		httpHeaders.set(SecurPharmConstants.HEADER_AUTHORIZATION, SecurPharmConstants.AUTHORIZATION_TYPE_BEARER + getAccessToken());

		final HttpEntity<?> entity = new HttpEntity<>(httpHeaders);

		return apiRestTemplate.exchange(uri.toUriString(), method, entity, String.class);
	}

	private UriComponentsBuilder getDecommisionPathBuilder(
			@NonNull final ProductData productData,
			@NonNull final DecommissionAction action)
	{
		final String encodedSerialNumber = Base64.getEncoder().encodeToString(productData.getSerialNumber().getBytes());
		final String encodedLot = Base64.getEncoder().encodeToString(productData.getLot().getBytes());
		return UriComponentsBuilder.fromPath(SecurPharmConstants.API_RELATIVE_PATH_PRODUCTS)
				.path(productData.getProductCode())
				.path(SecurPharmConstants.API_RELATIVE_PATH_PACKS)
				.path(encodedSerialNumber)
				.queryParam(SecurPharmConstants.QUERY_PARAM_CTX, UUID.randomUUID().toString())
				.queryParam(SecurPharmConstants.QUERY_PARAM_SID, config.getApplicationUUID())
				.queryParam(SecurPharmConstants.QUERY_PARAM_LOT, encodedLot)
				.queryParam(SecurPharmConstants.QUERY_PARAM_EXP, productData.getExpirationDate())
				.queryParam(SecurPharmConstants.QUERY_PARAM_PCS, productData.getProductCodeType().name())
				.queryParam(SecurPharmConstants.QUERY_PARAM_ACT, action.name());
	}

	private String getAccessToken()
	{
		return getAuthResponse().getAccessToken();
	}

	private synchronized AuthResponse getAuthResponse()
	{
		AuthResponse authResponse = _authResponse;

		if (authResponse == null
				|| authResponse.isExpired())
		{
			authResponse = _authResponse = authenticate(config);
		}

		return authResponse;
	}

	private static AuthResponse authenticate(@NonNull final SecurPharmConfig config)
	{
		try
		{
			final SSLContext sslContext = createSSLContext(config);

			final HttpClient client = HttpClients.custom()
					.setSSLContext(sslContext)
					.build();

			final RestTemplate restTemplate = new RestTemplate();
			restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(client));

			final HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

			final MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
			params.add(SecurPharmConstants.AUTH_PARAM_GRANT_TYPE, SecurPharmConstants.AUTH_PARAM_GRANT_TYPE_VALUE);
			params.add(SecurPharmConstants.AUTH_PARAM_CLIENT_ID, SecurPharmConstants.AUTH_PARAM_CLIENT_ID_VALUE);

			final HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

			final ResponseEntity<AuthResponse> response = restTemplate.postForEntity(config.getAuthBaseUrl() + SecurPharmConstants.AUTH_RELATIVE_PATH, request, AuthResponse.class);
			final AuthResponse authResponse = response.getBody();
			if (response.getStatusCode() == HttpStatus.OK)
			{
				return authResponse;
			}
			else
			{
				throw new AdempiereException(SecurPharmConstants.AUTHORIZATION_FAILED_MESSAGE)
						.appendParametersToMessage()
						.setParameter(SecurPharmConstants.HTTP_STATUS, response.getStatusCode())
						.setParameter(SecurPharmConstants.ERROR, authResponse.getError())
						.setParameter(SecurPharmConstants.ERROR_DESCRIPTION, authResponse.getErrorDescription());
			}
		}
		catch (final AdempiereException ex)
		{
			throw ex;
		}
		catch (final Exception ex)
		{
			final Throwable cause = AdempiereException.extractCause(ex);
			throw new AdempiereException(SecurPharmConstants.AUTHORIZATION_FAILED_MESSAGE, cause);
		}
	}

	private static SSLContext createSSLContext(final SecurPharmConfig config)
	{
		try
		{
			final char[] password = config.getKeystorePassword().toCharArray();
			final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
			final File key = new File(config.getCertificatePath());
			try (final InputStream in = new FileInputStream(key))
			{
				keyStore.load(in, password);
			}

			return SSLContextBuilder.create()
					.loadKeyMaterial(keyStore, password)
					.loadTrustMaterial(null, new TrustSelfSignedStrategy()).build();
		}
		catch (final Exception ex)
		{
			throw new AdempiereException("Failed creating SSL context for " + config, ex);
		}
	}
}

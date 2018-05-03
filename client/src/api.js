import querystring from 'querystring'

const API = {
	fetch(pathName, params, accept) {
		params = params || {}
		params.credentials = 'same-origin'

		params.headers = params.headers || {}
		params.headers.Accept = accept || '*/*'
		const authHeader = API.getAuthHeader()
		if (authHeader) {
			params.headers[authHeader[0]] = authHeader[1]
		}

		let promise = window.fetch(pathName, params)
					.then(response => {
						let isOk = response.ok

						if (API.inProgress === promise) {
							API.inProgress = null
						}

						if (response.headers.get('content-type') === 'application/json') {
							let respBody = response.json()
							if (!isOk) {
								return respBody.then(r => {
									r.status = response.status
									r.statusText = response.statusText
									if (!r.message) { r.message = r.error || 'You do not have permissions to perform this action' }
									return Promise.reject(r)
								})
							}
							return respBody
						}

						if (!isOk) {
							if (response.status === 500) {
								response.message = 'An Error occured! Please contact the administrator and let them know what you were doing to get this error'
							}
							response = Promise.reject(response)
						}

						return response
					})

		API.inProgress = promise
		return promise
	},

	send(url, data, params) {
		params = params || {}
		params.disableSubmits = typeof params.disableSubmits === 'undefined' ? true : params.disableSubmits
		params.method = params.method || 'POST'
		params.body = data
		params.headers = params.headers || {}

		return API.fetch(url, params)
	},

	sendJSON(url, data, params) {
		// Send data as JSON 
		const jsonData = JSON.stringify(data)
		params.headers = params.headers || {}
		params.headers['Content-Type'] = 'application/json'

		API.send(url, jsonData, params)
	},
	
	sendFile(url, data, params) {
		// send data as form data
		const formData = new FormData()
		Object.keys(data).forEach(key => formData.append(key, data[key]))

		return API.send(url, formData, params)
	},

	_queryCommon(query, variables, variableDef, output) {
		variables = variables || {}
		variableDef = variableDef || ''
		query = 'query ' + variableDef + ' { ' + query + ' }'
		output = output || ''
		return API.send('/graphql', {query, variables, output})
	},

	query(query, variables, variableDef) {
		return API._queryCommon(query, variables, variableDef).then(json => json.data)
	},

	queryExport(query, variables, variableDef, output) {
		return API._queryCommon(query, variables, variableDef, output).then(response => response.blob())
	},

	/**
	 * Creates a log entry on the server based on the following inputs
	 * - severity: one of 'DEBUG','ERROR','FATAL','INFO','WARN'
	 * - url: the context url
	 * - lineNr: line number of the error
	 * - message: The error/log message
	 */
	logOnServer(severity, url, lineNr, message)
	{
		API.send('/api/logging/log',[{severity: severity, url: url, lineNr: lineNr, message: message}])
	},

	loadFileAjaxSync(filePath, mimeType) {
		let xmlhttp=new XMLHttpRequest()
		xmlhttp.open("GET",filePath,false)
		const authHeader = API.getAuthHeader()
		if (authHeader) {
			xmlhttp.setRequestHeader(authHeader[0], authHeader[1])
		}
		if (mimeType != null) {
			if (xmlhttp.overrideMimeType) {
				xmlhttp.overrideMimeType(mimeType)
			}
		}
		xmlhttp.send()
		if (xmlhttp.status===200) {
			return xmlhttp.responseText
		}
		else {
			throw new Error("unable to load " + filePath)
		}
	},

	_getAuthParams: function() {
		const query = querystring.parse(window.location.search.slice(1))
		if (query.user && query.pass) {
			window.ANET_DATA.creds = {
				user: query.user,
				pass: query.pass
			}
		}
		return window.ANET_DATA.creds
	},

	addAuthParams: function(url) {
		const creds = API._getAuthParams()
		if (creds) {
			url += "?" + querystring.stringify(creds)
		}
		return url
	},

	getAuthHeader: function() {
		const creds = API._getAuthParams()
		if (creds) {
			return ['Authorization', 'Basic ' + new Buffer(`${creds.user}:${creds.pass}`).toString('base64')]
		}
		return null
	}
}

export default API

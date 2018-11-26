import React from 'react'
import Page, {mapDispatchToProps, propTypes as pagePropTypes} from 'components/Page'

import Breadcrumbs from 'components/Breadcrumbs'

import LocationForm from './Form'

import API from 'api'
import {Location} from 'models'

import { PAGE_PROPS_NO_NAV } from 'actions'
import { connect } from 'react-redux'

class LocationEdit extends Page {

	static propTypes = {
		...pagePropTypes,
	}

	state = {
		location: new Location(),
	}

	constructor(props) {
		super(props, PAGE_PROPS_NO_NAV)
	}

	fetchData(props) {
		return API.query(/* GraphQL */`
			location(uuid:"${props.match.params.uuid}") {
				uuid, name, status, lat, lng
			}
		`).then(data => {
			this.setState({location: new Location(data.location)})
		})
	}

	render() {
		const { location } = this.state
		return (
			<div>
				<Breadcrumbs items={[[`Location ${location.name}`, Location.pathFor(location)], ["Edit", Location.pathForEdit(location)]]} />
				<LocationForm edit initialValues={location} title={`Location ${location.name}`} />
			</div>
		)
	}
}

export default connect(null, mapDispatchToProps)(LocationEdit)

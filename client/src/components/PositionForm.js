import React, {Component, PropTypes} from 'react'

import {Table} from 'react-bootstrap'
import autobind from 'autobind-decorator'

import Form from 'components/Form'
import Messages from 'components/Messages'
import Autocomplete from 'components/Autocomplete'
import {Position} from 'models'

export default class PositionForm extends Component {
	static propTypes = {
		position: PropTypes.object,
		onChange: PropTypes.func,
		onSubmit: PropTypes.func,
		edit: PropTypes.bool,
		submitText: PropTypes.string,
		error: PropTypes.object,
	}

	constructor(props) {
		super(props);

		this.state = {
			position: props.position
		}
	}

	componentWillReceiveProps(props) {
		this.setState({position: props.position})
	}

	render() {
		let {onChange, onSubmit, submitText, error, success} = this.props
		let position = this.state.position;
		let relationshipPositionType = (position.type === "PRINCIPAL") ? "ADVISOR" : "PRINCIPAL"

		//TODO: only allow you to set positon to admin if you are an admin.

		return <Form formFor={position} onChange={onChange}
				onSubmit={onSubmit} horizontal
				submitText={submitText} >

			<Messages error={error} success={success} />

			<fieldset>
				<legend>Create a new Position</legend>

				<Form.Field id="organization" value={position.organization}>
					<Autocomplete valueKey="shortName"
						placeholder="Select the organization for this position"
						url="/api/organizations/search"
					/>
				</Form.Field>

				{position.organization && position.organization.type === "PRINCIPAL_ORG" &&
					<Form.Field type="static" value="PRINCIPAL" label="Type" id="type" >Afghan Principal</Form.Field> }

				{position.organization && position.organization.type === "ADVISOR_ORG" &&
					<Form.Field id="type" componentClass="select">
						<option value="ADVISOR">Advisor</option>
						<option value="SUPER_USER">Super User</option>
						<option value="ADMINISTRATOR">Administrator</option>
					</Form.Field>
				}

				<Form.Field id="code" placeholder="Postion ID or Number" />
				<Form.Field id="name" label="Position Name" placeholder="Name/Description of Position"/>

				<Form.Field id="person" >
					<Autocomplete valueKey="name"
						placeholder="Select the person in this position"
						url="/api/people/search"
						queryParams={position.type ? {role: position.type} : {}}
					/>
				</Form.Field>
			</fieldset>

			<fieldset>
				<legend>Assigned Position Relationships</legend>

				<Form.Field id="associatedPositions">
					<Autocomplete
						placeholder="Assign new Position Relationship"
						objectType={Position}
						fields={"id, name, type, person { id, name, rank }"}
						template={pos =>
							<span>{pos.name} ({(pos.person) ? pos.person.name : <i>empty</i>})</span>
						}
						onChange={this.addPositionRelationship}
						clearOnSelect={true}
						queryParams={{type: relationshipPositionType}} />

					<Table hover striped>
						<thead>
							<tr>
								<th></th>
								<th>Name</th>
								<th>Position</th>
							</tr>
						</thead>
						<tbody>
						{Position.map(position.associatedPositions, relPos =>
							<tr key={relPos.id}>
								<td onClick={this.removePositionRelationship.bind(this, relPos)}>
									<span style={{cursor: 'pointer'}}>⛔️</span>
								</td>
								<td>{relPos.person && relPos.person.name}</td>
								<td>{relPos.name}</td>
							</tr>
						)}
						</tbody>
					</Table>
				</Form.Field>
				<div className="todo">Should be able to search by person name too, but only people in positions.... and then pull up their position... </div>
			</fieldset>

			<fieldset>
				<legend>Additional Information</legend>
				<Form.Field id="location">
					<Autocomplete valueKey="name" placeholder="Position Location" url="/api/locations/search" />
				</Form.Field>
			</fieldset>
		</Form>
	}

	@autobind
	addPositionRelationship(newRelatedPos)  {
		let position = this.state.position
		let rels = position.associatedPositions;

		if (!rels.find(relPos => relPos.id === newRelatedPos.id)) {
			let newRels = rels.splice();
			newRels.push(new Position(newRelatedPos))

			position.associatedPositions = newRels;
			this.setState({position})
			this.props.onChange();
		}
	}

	@autobind
	removePositionRelationship(relToDelete) {
		let position = this.state.position;
		let rels = position.associatedPositions;
		let index = rels.findIndex(rel => rel.id === relToDelete.id)

		if (index !== -1) {
			rels.splice(index, 1)
			this.setState({position})
		}
	}
}

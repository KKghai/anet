import React, {PropTypes} from 'react'
import {Button, Table} from 'react-bootstrap'
import autobind from 'autobind-decorator'

import ValidatableFormWrapper from 'components/ValidatableFormWrapper'
import Fieldset from 'components/Fieldset'
import Form from 'components/Form'
import ButtonToggleGroup from 'components/ButtonToggleGroup'
import Autocomplete from 'components/Autocomplete'
import TaskSelector from 'components/TaskSelector'
import LinkTo from 'components/LinkTo'
import History from 'components/History'
import Messages from 'components/Messages'

import API from 'api'
import Settings from 'Settings'
import {Position, Organization} from 'models'

import DictionaryField from '../../HOC/DictionaryField'

import REMOVE_ICON from 'resources/delete.png'

export default class OrganizationForm extends ValidatableFormWrapper {
	static propTypes = {
		organization: PropTypes.object,
		edit: PropTypes.bool,
	}

	static contextTypes = {
		currentUser: PropTypes.object.isRequired,
	}

	constructor(props) {
		super(props)
		this.state = {
			error: null,
		}
		this.IdentificationCodeFieldWithLabel = DictionaryField(Form.Field)
		this.LongNameWithLabel = DictionaryField(Form.Field)
	}

	render() {
		let {organization, edit} = this.props
		let {approvalSteps} = organization
		let currentUser = this.context.currentUser 
		let isAdmin = currentUser && currentUser.isAdmin()
		let isPrincipalOrg = (organization.type === Organization.TYPE.PRINCIPAL_ORG)
		const {ValidatableForm, RequiredField} = this

		const orgSettings = isPrincipalOrg ? Settings.fields.principal.org : Settings.fields.advisor.org

		return <ValidatableForm formFor={organization}
			onChange={this.onChange}
			onSubmit={this.onSubmit}
			submitText="Save organization"
			horizontal>

			<Messages error={this.state.error} />

			<Fieldset title={edit ? `Edit Organization ${organization.shortName}` : "Create a new Organization"}>
				<Form.Field id="type">
					<ButtonToggleGroup>
						<Button id="advisorOrgButton" disabled={!isAdmin} value={Organization.TYPE.ADVISOR_ORG}>{Settings.fields.advisor.org.name}</Button>
						<Button id="principalOrgButton" disabled={!isAdmin} value={Organization.TYPE.PRINCIPAL_ORG}>{Settings.fields.principal.org.name}</Button>
					</ButtonToggleGroup>
				</Form.Field>

				<Form.Field id="parentOrg" label="Parent organization">
					<Autocomplete valueKey="shortName" disabled={isPrincipalOrg && !isAdmin}
						placeholder="Start typing to search for a higher level organization..."
						url="/api/organizations/search"
						queryParams={{type: organization.type}}
					/>
				</Form.Field>

				<RequiredField id="shortName" label="Name" placeholder="e.g. EF1.1" />
				<this.LongNameWithLabel dictProps={orgSettings.longName} id="longName" disabled={isPrincipalOrg && !isAdmin} />

				<Form.Field id="status" >
					<ButtonToggleGroup>
						<Button id="statusActiveButton" value={ Organization.STATUS.ACTIVE }>Active</Button>
						<Button id="statusInactiveButton" value={ Organization.STATUS.INACTIVE }>Inactive</Button>
					</ButtonToggleGroup>
				</Form.Field>

				<this.IdentificationCodeFieldWithLabel dictProps={orgSettings.identificationCode} id="identificationCode" disabled={!isAdmin}/>
			</Fieldset>

			{organization.isAdvisorOrg() && <div>
				<Fieldset title="Approval process">
					<Button className="pull-right" onClick={this.addApprovalStep} bsStyle="primary" id="addApprovalStepButton" >
						Add an Approval Step
					</Button>

					{approvalSteps && approvalSteps.map((step, index) =>
						this.renderApprovalStep(step, index)
					)}
				</Fieldset>

				{ organization.isTaskEnabled() &&
					<TaskSelector tasks={organization.tasks} onChange={this.onChange} />
				}
			</div>}
		</ValidatableForm>
	}

	renderApprovalStep(step, index) {
		const approvers = step.approvers
		const { RequiredField } = this

		return <Fieldset title={`Step ${index + 1}`} key={index}>
			<Button className="pull-right" onClick={this.removeApprovalStep.bind(this, index)}>
				X
			</Button>

			<RequiredField id="approvalStepName"
				label="Step name"
				value={step.name}
				onChange={(event) => this.setStepName(index, event)} />

			<Form.Field id="addApprover" label="Add an approver" value={approvers}>
				<Autocomplete valueKey="name"
					placeholder="Search for the approver's position"
					objectType={Position}
					fields="id, name, code, type, person { id, name, rank }"
					template={pos => {
						let components = []
						pos.person && components.push(pos.person.name)
						pos.name && components.push(pos.name)
						pos.code && components.push(pos.code)
						return <span>{components.join(' - ')}</span>
					}}
					queryParams={{status: Position.STATUS.ACTIVE, type: [Position.TYPE.ADVISOR, Position.TYPE.SUPER_USER, Position.TYPE.ADMINISTRATOR], matchPersonName: true}}
					onChange={this.addApprover.bind(this, index)}
					clearOnSelect={true} />

				<Table striped>
					<thead>
						<tr>
							<th>Name</th>
							<th>Position</th>
							<th></th>
						</tr>
					</thead>
					<tbody>
						{approvers.map((approver, approverIndex) =>
							<tr key={approver.id} id={`step_${index}_approver_${approverIndex}`} >
								<td><LinkTo person={approver.person} target="_blank" /></td>
								<td><LinkTo position={approver} target="_blank" /></td>
								<td onClick={this.removeApprover.bind(this, approver, index)}>
									<span style={{cursor: 'pointer'}}><img src={REMOVE_ICON} height={14} alt="Remove approver" /></span>
								</td>
							</tr>
						)}
					</tbody>
				</Table>
			</Form.Field>
		</Fieldset>
	}

	@autobind
	addApprover(index, position) {
		if (!position || !position.id) {
			return
		}

		let org = this.props.organization
		let step = org.approvalSteps[index]
		let newApprovers = step.approvers.slice()
		newApprovers.push(position)
		step.approvers = newApprovers

		this.onChange()
	}

	@autobind
	removeApprover(approver, index) {
		let step = this.props.organization.approvalSteps[index]
		let approvers = step.approvers
		let approverIndex = approvers.findIndex(m => m.id === approver.id )

		if (approverIndex !== -1) {
			approvers.splice(approverIndex, 1)
			this.onChange()
		}
	}

	@autobind
	setStepName(index, event) {
		let name = event && event.target ? event.target.value : event
		let step = this.props.organization.approvalSteps[index]
		step.name = name

		this.onChange()
	}

	@autobind
	addApprovalStep() {
		let org = this.props.organization
		let approvalSteps = org.approvalSteps || []
		approvalSteps.push({name: '', approvers: []})

		this.onChange()
	}

	@autobind
	removeApprovalStep(index) {
		let steps = this.props.organization.approvalSteps
		steps.splice(index, 1)
		this.onChange()
	}

	@autobind
	onChange() {
		this.forceUpdate()
	}

	@autobind
	onSubmit(event) {
		let organization = Object.without(this.props.organization, 'childrenOrgs', 'positions', 'approvalStepName')
		if (organization.parentOrg) {
			organization.parentOrg = {id: organization.parentOrg.id}
		}

		let url = `/api/organizations/${this.props.edit ? 'update' : 'new'}`
		API.send(url, organization, {disableSubmits: true})
			.then(response => {
				if (response.code) {
					throw response.code
				}

				if (response.id) {
					organization.id = response.id
				}

				History.replace(Organization.pathForEdit(organization), false)
				History.push(Organization.pathFor(organization), {
					success: 'Organization saved successfully',
					skipPageLeaveWarning: true
				})
			}).catch(error => {
				this.setState({error})
				window.scrollTo(0, 0)
			})
	}
}

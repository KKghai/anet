import Model from 'components/Model'
import Settings from 'Settings'

export default class Organization extends Model {
	static resourceName = 'Organization'
	static listName = 'organizationList'

	static TYPE = {
		ADVISOR_ORG: 'ADVISOR_ORG',
		PRINCIPAL_ORG: 'PRINCIPAL_ORG'
	}

	static schema = {
		shortName: '',
		longName: '',
		identificationCode: null,
		type: '',
		parentOrg: null,
		childrenOrgs: [],
		approvalSteps: [],
		positions: [],
		tasks: []
	}

	isAdvisorOrg() {
		return this.type === Organization.TYPE.ADVISOR_ORG
	}

	static humanNameOfType(type) {

		if (type === Organization.TYPE.PRINCIPAL_ORG) {
			return Settings.fields.principal.org.name
		}
		else {
			return Settings.fields.advisor.org.name
		} // TODO do not assume that if not of type TYPE.PRINCIPAL_ORG it is an advisor
	}

	humanNameOfType(type) {
		return Organization.humanNameOfType(this.type)
	}

	toString() {
		return this.shortName || this.longName || this.identificationCode
	}
}

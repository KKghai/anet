import React, {Component, PropTypes} from 'react'
import ReactDOM from 'react-dom'
import {Form as BSForm, Button} from 'react-bootstrap'
import autobind from 'autobind-decorator'

import FormField from 'components/FormField'

export default class Form extends Component {
	static propTypes = Object.assign({}, BSForm.propTypes, {
		formFor: PropTypes.object,
		static: PropTypes.bool,
		submitText: PropTypes.oneOfType([PropTypes.string, PropTypes.bool]),
		submitOnEnter: PropTypes.bool,
		submitDisabled: PropTypes.bool,
		onSubmit: PropTypes.func,
		onChange: PropTypes.func,
	})

	static defaultProps = {
		static: false,
		submitText: 'Save',
		submitOnEnter: false,
	}

	static childContextTypes = {
		formFor: PropTypes.object,
		form: PropTypes.object,
	}

	getChildContext() {
		return {
			formFor: this.props.formFor,
			form: this,
		}
	}

	componentDidMount() {
		let container = ReactDOM.findDOMNode(this.refs.container)
		let focusElement = container.querySelector('[data-focus]')
		if (focusElement) focusElement.focus()
	}

	render() {
		let {children, submitText, submitOnEnter, submitDisabled, ...bsProps} = this.props
		bsProps = Object.without(bsProps, 'formFor', 'static')

		if (this.props.static) {
			submitText = false
			bsProps.componentClass = "div"
		}

		if (!submitOnEnter) {
			bsProps.onKeyDown = this.preventEnterKey
		}

		let showSubmit = bsProps.onSubmit && submitText !== false
		bsProps.onSubmit = this.onSubmit

		return (
			<BSForm {...bsProps} ref="container">
				{children}

				{showSubmit &&
					<div className="form-bottom-submit">
						<Button bsStyle="primary" bsSize="large" type="submit" disabled={submitDisabled} id="formBottomSubmit">
							{submitText}
						</Button>
					</div>
				}
			</BSForm>
		)
	}

	preventEnterKey(event) {
		if (event.key === 'Enter') {
			event.preventDefault()
			event.stopPropagation()
		}
	}

	@autobind
	onSubmit(event) {
		event.stopPropagation()
		event.preventDefault()

		this.props.onSubmit && this.props.onSubmit(event)
	}
}

// just a little sugar to make importing and building forms easier
Form.Field = FormField

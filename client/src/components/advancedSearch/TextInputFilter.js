import autobind from "autobind-decorator"
import _isEqualWith from "lodash/isEqualWith"
import PropTypes from "prop-types"
import React, { Component } from "react"
import { FormControl } from "react-bootstrap"
import utils from "utils"

export default class TextInputFilter extends Component {
  static propTypes = {
    queryKey: PropTypes.string.isRequired,

    // Passed by the SearchFilterDisplay row
    asFormField: PropTypes.bool
    // Passed by the SearchFilter row
    // value
    // onChange
  }

  static defaultProps = {
    asFormField: true
  }

  constructor(props) {
    super(props)
    this.state = {
      value: props.value || { value: "" }
    }
  }

  componentDidMount() {
    this.updateFilter()
  }

  componentDidUpdate(prevProps, prevState) {
    if (
      !_isEqualWith(
        prevProps.value,
        this.props.value,
        utils.treatFunctionsAsEqual
      )
    ) {
      this.setState({ value: this.props.value }, this.updateFilter)
    }
  }

  render() {
    return !this.props.asFormField ? (
      <React.Fragment>{this.state.value.value}</React.Fragment>
    ) : (
      <FormControl value={this.state.value.value} onChange={this.onChange} />
    )
  }

  @autobind
  onChange(event) {
    let { value } = this.state
    value.value = event.target.value
    this.setState({ value }, this.updateFilter)
  }

  @autobind
  toQuery() {
    return { [this.props.queryKey]: this.state.value.value }
  }

  @autobind
  updateFilter() {
    if (this.props.asFormField) {
      let { value } = this.state
      value.toQuery = this.toQuery
      this.props.onChange(value)
    }
  }

  @autobind
  deserialize(query, key) {
    if (query[this.props.queryKey]) {
      const toQueryValue = { [this.props.queryKey]: query[this.props.queryKey] }
      return {
        key: key,
        value: {
          value: query[this.props.queryKey],
          toQuery: () => toQueryValue
        }
      }
    }
    return null
  }
}

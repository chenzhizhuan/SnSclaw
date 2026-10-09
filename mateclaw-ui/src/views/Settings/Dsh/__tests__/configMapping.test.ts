import { describe, expect, it } from 'vitest'
import {
  createEmptyDshConfigForm,
  formToManagedConfig,
  managedConfigToForm,
} from '../configMapping'

describe('DSH managed configuration mapping', () => {
  it('maps canonical managed keys into the settings form', () => {
    expect(managedConfigToForm({
      'dsh.executable_path': '/opt/dsh/bin',
      'dsh.cordis_config_path': '/opt/dsh/cordis.yml',
      'dsh.working_directory': '/srv/workspace',
      'dsh.base_url': 'https://api.deepseek.com',
      'dsh.model_name': 'deepseek-chat',
      'dsh.api_key': 'plain-test-key',
    })).toEqual({
      profile: 'sdk',
      patch_paths: '[]',
      home_root: '',
      executable_path: '/opt/dsh/bin',
      cordis_config_path: '/opt/dsh/cordis.yml',
      working_directory: '/srv/workspace',
      base_url: 'https://api.deepseek.com',
      model_name: 'deepseek-chat',
      api_key: 'plain-test-key',
    })
  })

  it('uses empty form values for missing managed keys and masked secrets', () => {
    expect(managedConfigToForm({ 'dsh.api_key': '****abcd' }))
      .toEqual(createEmptyDshConfigForm())
  })

  it('maps every settings form field to the canonical backend key', () => {
    const payload = formToManagedConfig({
      profile: 'sdk',
      patch_paths: '["/tmp/my patch.yml"]',
      home_root: '/tmp/dsh-home',
      executable_path: '/opt/dsh/bin',
      cordis_config_path: '/opt/dsh/cordis.yml',
      working_directory: '/srv/workspace',
      base_url: 'https://api.deepseek.com',
      model_name: 'deepseek-chat',
      api_key: '',
    })

    expect(payload).toEqual({
      'dsh.profile': 'sdk',
      'dsh.patch_paths': '["/tmp/my patch.yml"]',
      'dsh.home_root': '/tmp/dsh-home',
      'dsh.executable_path': '/opt/dsh/bin',
      'dsh.cordis_config_path': '/opt/dsh/cordis.yml',
      'dsh.working_directory': '/srv/workspace',
      'dsh.base_url': 'https://api.deepseek.com',
      'dsh.model_name': 'deepseek-chat',
      'dsh.api_key': '',
    })
    expect(payload).not.toHaveProperty('executable_path')
  })
})

it('rejects patch lists that are not JSON arrays of absolute paths', () => {
  const form = createEmptyDshConfigForm()
  form.patch_paths = '["relative.yml"]'
  expect(() => formToManagedConfig(form)).toThrow()
  form.patch_paths = '{}'
  expect(() => formToManagedConfig(form)).toThrow()
})

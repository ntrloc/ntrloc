import { useState } from 'react'
import Button from '../../controls/button/Button'
import ModalPopupView from '../../controls/modal/ModalPopupView'
import { activateSamlNextKey, addSamlNextKey, removeSamlKey } from './SecurityDelegate'

const ROLE_LABELS = { ACTIVE: 'Active', NEXT: 'Next', RETIRING: 'Retiring' }

// What each step asks of the IdP's admin, shown under the keys.
const NEXT_STEP = {
  NEXT: "Give the IdP ntrloc's updated metadata, then activate the next key.",
  RETIRING:
    "Once the IdP has ntrloc's updated metadata, retire the old key. Until then it still decrypts what the IdP sends.",
}

// ntrloc's key pairs for one SAML provider, and rotating them: add a next key (generated, or the
// admin's own PEM key and certificate), activate it, retire the old one -- or discard a next key. Changes apply at once,
// independently of the provider form's Save; onChanged gets the provider as it then is.
function SamlKeysView({ provider, onChanged }) {
  const [adding, setAdding] = useState(null)
  const [upload, setUpload] = useState({ privateKey: '', certificate: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [confirming, setConfirming] = useState(null)
  const roles = new Set(provider.keys.map((key) => key.role))

  async function run(action) {
    setBusy(true)
    setError(null)
    try {
      onChanged(await action())
      setAdding(null)
      setUpload({ privateKey: '', certificate: '' })
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const confirmations = {
    activate: {
      message: "Activate the next key? ntrloc signs with it from now on, so the IdP must already have ntrloc's updated metadata.",
      action: () => activateSamlNextKey(provider.registrationId),
    },
    next: {
      message: 'Discard the next key? It was never activated, so nothing depends on it.',
      action: () => removeSamlKey(provider.registrationId, 'next'),
    },
    retiring: {
      message: "Retire the old key? Anything the IdP still encrypts to it can't be read any more.",
      action: () => removeSamlKey(provider.registrationId, 'retiring'),
    },
  }

  return (
    <saml-keys-view>
      <h5>Keys</h5>
      <table className="sign-in-providers">
        <thead>
          <tr>
            <th>Role</th>
            <th>Certificate</th>
            <th>Added</th>
            <th>Expires</th>
            <th aria-label="Actions" />
          </tr>
        </thead>
        <tbody>
          {provider.keys.map((key) => (
            <tr key={key.role}>
              <td>{ROLE_LABELS[key.role]}</td>
              <td>{key.certificateSubject}</td>
              <td>{new Date(key.createdAt).toLocaleString()}</td>
              <td>{new Date(key.certificateExpires).toLocaleDateString()}</td>
              <td>
                {key.role === 'NEXT' && (
                  <>
                    <Button onClick={() => setConfirming('activate')} disabled={busy}>
                      Activate
                    </Button>{' '}
                    <Button variant="secondary" onClick={() => setConfirming('next')} disabled={busy}>
                      Discard
                    </Button>
                  </>
                )}
                {key.role === 'RETIRING' && (
                  <Button variant="secondary" onClick={() => setConfirming('retiring')} disabled={busy}>
                    Retire
                  </Button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {[...roles].filter((role) => NEXT_STEP[role]).map((role) => (
        <p key={role} className="security-card-hint">
          {NEXT_STEP[role]}
        </p>
      ))}

      {!roles.has('NEXT') && adding === null && (
        <saml-keys-actions>
          <Button variant="secondary" onClick={() => run(() => addSamlNextKey(provider.registrationId, {}))} disabled={busy}>
            Generate next key
          </Button>
          <Button variant="secondary" onClick={() => setAdding('upload')} disabled={busy}>
            Upload next key…
          </Button>
        </saml-keys-actions>
      )}

      {adding === 'upload' && (
        <saml-key-upload>
          <label>
            <span className="input-control-name">Private key (PEM)</span>
            <textarea
              value={upload.privateKey}
              onChange={(e) => setUpload({ ...upload, privateKey: e.target.value })}
              placeholder="-----BEGIN PRIVATE KEY-----"
              spellCheck={false}
              rows={5}
            />
          </label>
          <label>
            <span className="input-control-name">Certificate (PEM)</span>
            <textarea
              value={upload.certificate}
              onChange={(e) => setUpload({ ...upload, certificate: e.target.value })}
              placeholder="-----BEGIN CERTIFICATE-----"
              spellCheck={false}
              rows={5}
            />
          </label>
          <saml-keys-actions>
            <Button onClick={() => run(() => addSamlNextKey(provider.registrationId, upload))} disabled={busy}>
              Upload
            </Button>
            <Button variant="secondary" onClick={() => setAdding(null)} disabled={busy}>
              Cancel
            </Button>
          </saml-keys-actions>
        </saml-key-upload>
      )}

      {error && <p className="security-card-error">{error}</p>}

      <ModalPopupView
        open={confirming !== null}
        message={confirming ? confirmations[confirming].message : ''}
        onConfirm={() => {
          const { action } = confirmations[confirming]
          setConfirming(null)
          run(action)
        }}
        onCancel={() => setConfirming(null)}
      />
    </saml-keys-view>
  )
}

export default SamlKeysView

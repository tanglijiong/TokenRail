const tokens = { investorA: 'investor-a-token', investorB: 'investor-b-token', operator: 'operator-token' };
const requestedActor = new URLSearchParams(location.search).get('actor');
if (requestedActor && tokens[requestedActor]) localStorage.setItem('tokenrail-token', tokens[requestedActor]);
const currentToken = () => localStorage.getItem('tokenrail-token') || tokens.investorB;
const key = prefix => `${prefix}-${crypto.randomUUID()}`;
const byId = id => document.getElementById(id);
const short = value => value ? `${value.slice(0, 10)}…${value.slice(-6)}` : '—';
const money = value => value == null ? '—' : (BigInt(value) / 100n).toLocaleString('zh-CN', {minimumFractionDigits: 2}) + ' CNY';
const id32 = async () => {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(crypto.randomUUID()));
  return '0x' + [...new Uint8Array(bytes)].map(value => value.toString(16).padStart(2, '0')).join('');
};
async function api(path, options = {}) {
  const headers = {'Authorization': `Bearer ${currentToken()}`, ...(options.headers || {})};
  if (options.body) headers['Content-Type'] = 'application/json';
  const response = await fetch('/api/v1' + path, {...options, headers});
  const text = await response.text();
  let body; try { body = text ? JSON.parse(text) : {}; } catch { body = {message: text}; }
  if (!response.ok) throw new Error(body.message || `${response.status} ${response.statusText}`);
  return body;
}
function toast(message, error = false) {
  const node = byId('toast'); node.textContent = message; node.className = error ? 'error' : '';
  node.style.display = 'block'; setTimeout(() => node.style.display = 'none', 4500);
}
function busy(form, state) { form.querySelectorAll('button').forEach(button => button.disabled = state); }
function nav() {
  document.querySelectorAll('nav a').forEach(a => { if (location.pathname.endsWith(a.getAttribute('href'))) a.classList.add('active'); });
  const picker = byId('actor');
  if (picker) {
    picker.value = Object.entries(tokens).find(([, token]) => token === currentToken())?.[0] || 'investorB';
    picker.onchange = () => { localStorage.setItem('tokenrail-token', tokens[picker.value]); location.reload(); };
  }
}
async function loadSession() {
  const session = await api('/session'); window.session = session;
  document.querySelectorAll('[data-session]').forEach(node => node.textContent = session[node.dataset.session] ?? '—');
  document.querySelectorAll('[data-contract]').forEach(node => node.textContent = session.contracts[node.dataset.contract] ?? '—');
  byId('role-badge') && (byId('role-badge').textContent = session.roles.length ? session.roles.join(' · ') : 'ACCOUNT');
  return session;
}
function wireForm(id, handler) {
  const form = byId(id); if (!form) return;
  form.addEventListener('submit', async event => {
    event.preventDefault(); busy(form, true);
    try { await handler(new FormData(form)); toast('请求已受理。请在操作记录中等待链上确认。'); }
    catch (error) { toast(error.message, true); }
    finally { busy(form, false); }
  });
}
async function pollOperation(operationId, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const operation = await api(`/operations/${operationId}`);
    if (['SUCCEEDED','COMPLETED','CREDITED','REVERTED'].includes(operation.status)) return operation;
    await new Promise(resolve => setTimeout(resolve, 800));
  }
  throw new Error('操作仍在处理中，可稍后从操作记录查看');
}
const stateName = (kind, value) => (kind === 'SETTLEMENT'
  ? ['NONE','CREATED','READY','SETTLED','CANCELLED','EXPIRED']
  : ['NONE','FUNDED','RELEASED','REFUNDED'])[value] || `UNKNOWN(${value})`;

async function dashboard() {
  const session = await loadSession();
  byId('network-state').innerHTML = session.headBlock >= session.indexedBlock
    ? '<span class="badge ok"><span class="dot"></span>RPC 可读</span>' : '<span class="badge warn">同步中</span>';
  byId('lag').textContent = String(Math.max(0, session.headBlock - (session.indexedBlock || 0)));
  const positions = await api('/positions');
  byId('positions').innerHTML = positions.positions.map(item => `<tr><td class="mono">${short(item.walletAddress)}</td><td>${item.symbol}</td><td>${item.balance}</td><td>${item.blockNumber}</td></tr>`).join('') || '<tr><td colspan="4">当前没有投影持仓</td></tr>';
}

async function settlementPage() {
  const session = await loadSession();
  ['seller','issue-recipient'].forEach(id => byId(id) && (byId(id).value = session.walletAddress));
  byId('cash-address').value = session.contracts.cash; byId('bond-address').value = session.contracts.bond;
  byId('dvp-address').value = session.contracts.dvp;
  wireForm('issue-form', async data => {
    const result = await api('/bond-issues', {method:'POST', headers:{'Idempotency-Key':key('issue')}, body:JSON.stringify({issueId:await id32(), recipient:data.get('recipient'), quantity:data.get('quantity')})});
    byId('last-operation').textContent = result.operationId;
  });
  wireForm('allowance-form', async data => {
    const asset = data.get('asset') === 'cash' ? session.contracts.cash : session.contracts.bond;
    const result = await api('/allowances', {method:'POST', headers:{'Idempotency-Key':key('approve')}, body:JSON.stringify({assetAddress:asset, spender:session.contracts.dvp, amount:data.get('amount')})});
    byId('last-operation').textContent = result.operationId;
  });
  wireForm('settlement-form', async data => {
    const id = await id32();
    const result = await api('/settlements', {method:'POST', headers:{'Idempotency-Key':key('settlement')}, body:JSON.stringify({instructionId:id,buyer:data.get('buyer'),bondQuantity:data.get('bond'),cashAmount:data.get('cash'),expiry:new Date(data.get('expiry')).toISOString(),externalTradeRef:data.get('reference')})});
    byId('settlement-id').value = id; byId('last-operation').textContent = result.operationId;
  });
  wireForm('settlement-lookup', async data => renderResource(await api(`/settlements/${data.get('id')}`), 'settlement-result'));
  document.querySelectorAll('[data-settlement-action]').forEach(button => button.onclick = async () => {
    try { const id = byId('settlement-id').value; const result = await api(`/settlements/${id}/${button.dataset.settlementAction}`, {method:'POST',headers:{'Idempotency-Key':key(button.dataset.settlementAction)}}); byId('last-operation').textContent = result.operationId; toast('操作已进入持久化队列'); }
    catch (error) { toast(error.message, true); }
  });
  const requestedId = new URLSearchParams(location.search).get('id');
  if (requestedId) {
    byId('settlement-id').value = requestedId;
    renderResource(await api(`/settlements/${requestedId}`), 'settlement-result');
  }
}

async function paymentPage() {
  const session = await loadSession(); byId('escrow-address').value = session.contracts.escrow;
  wireForm('payment-allowance', async data => {
    const result = await api('/allowances', {method:'POST',headers:{'Idempotency-Key':key('escrow-approve')},body:JSON.stringify({assetAddress:session.contracts.cash,spender:session.contracts.escrow,amount:data.get('amount')})});
    byId('last-operation').textContent = result.operationId;
  });
  wireForm('payment-form', async data => {
    const id = await id32(); const result = await api('/payments', {method:'POST',headers:{'Idempotency-Key':key('payment')},body:JSON.stringify({paymentId:id,payee:data.get('payee'),attestor:data.get('attestor'),amount:data.get('amount'),deadline:new Date(data.get('deadline')).toISOString()})});
    byId('payment-id').value=id; byId('last-operation').textContent=result.operationId;
  });
  wireForm('payment-lookup', async data => renderResource(await api(`/payments/${data.get('id')}`), 'payment-result'));
  document.querySelectorAll('[data-payment-action]').forEach(button => button.onclick = async () => {
    try { const id=byId('payment-id').value; const result=await api(`/payments/${id}/${button.dataset.paymentAction}`, {method:'POST',headers:{'Idempotency-Key':key(button.dataset.paymentAction)}}); byId('last-operation').textContent=result.operationId; toast('操作已进入持久化队列'); }
    catch(error){toast(error.message,true);}
  });
  const requestedId = new URLSearchParams(location.search).get('id');
  if (requestedId) {
    byId('payment-id').value = requestedId;
    renderResource(await api(`/payments/${requestedId}`), 'payment-result');
  }
}
function renderResource(resource, id) {
  const chain = resource.chain; chain.stateName = stateName(resource.resourceKind, chain.state);
  byId(id).textContent = JSON.stringify(resource, null, 2);
}
async function operationsPage() {
  const session = await loadSession();
  async function refresh() {
    const rows = await api('/operations');
    byId('operations').innerHTML = rows.map(item => `<tr><td class="mono">${short(item.operation_id)}</td><td>${item.action || item.kind}</td><td><span class="badge ${['SUCCEEDED','COMPLETED','CREDITED'].includes(item.status)?'ok':''}">${item.status}</span></td><td class="mono">${short(item.tx_hash)}</td><td>${item.mined_block || '—'}</td></tr>`).join('') || '<tr><td colspan="5">暂无操作</td></tr>';
  }
  byId('refresh').onclick=()=>refresh().catch(error=>toast(error.message,true)); await refresh();
  byId('catchup').onclick=async()=>toast('索引器按配置自动追块；当前落后 '+(session.headBlock-session.indexedBlock)+' 块');
  wireForm('reconcile-form', async () => { const result=await api('/reconciliations',{method:'POST',headers:{'Idempotency-Key':key('reconcile')}}); byId('job-result').textContent=JSON.stringify(result,null,2); });
  wireForm('rebuild-form', async () => { const result=await api('/projections/rebuild',{method:'POST',headers:{'Idempotency-Key':key('rebuild')}}); byId('job-result').textContent=JSON.stringify(result,null,2); });
}

document.addEventListener('DOMContentLoaded', async () => {
  nav();
  try {
    const page=document.body.dataset.page;
    if(page==='dashboard') await dashboard();
    if(page==='settlement') await settlementPage();
    if(page==='payment') await paymentPage();
    if(page==='operations') await operationsPage();
  } catch(error) { toast(error.message,true); }
});

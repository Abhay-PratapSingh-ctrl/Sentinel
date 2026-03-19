export const VAULT   = "0x644c0deb81e8c40abfae2c549e94ac770a938e81" as const;
export const SUSD    = "0xe7e85d0e7d22cc53322e492b1c5d97e2132de731" as const;
export const USDT    = "0xAfBDeD88916ea0DC2F4882968Cc6C3E3202402c5" as const;
export const ORACLE  = "0x0ebfb51f5a60d9274b55f53eba99beff64b88e1e" as const;
export const EXPLORER = "https://polkadot-hub-testnet.blockscout.com";
export const PYTH_URL = "https://hermes.pyth.network/v2/updates/price/latest?ids[]=0xca3eed9b267293f6595901c734c7525ce8ef49adafe8284606ceb307afa2ca5b";

export const VAULT_ABI = [
  { name:"getPosition",        type:"function", stateMutability:"view",       inputs:[{name:"",type:"address"}], outputs:[{type:"uint256"},{type:"uint256"},{type:"uint256"},{type:"uint256"},{type:"uint256"},{type:"bool"},{type:"bool"},{type:"uint256"}] },
  { name:"getHealthFactor",    type:"function", stateMutability:"view",       inputs:[{name:"",type:"address"}], outputs:[{type:"uint256"}] },
  { name:"depositCollateral",  type:"function", stateMutability:"payable",    inputs:[], outputs:[] },
  { name:"withdrawCollateral", type:"function", stateMutability:"nonpayable", inputs:[{name:"amount",type:"uint256"}], outputs:[] },
  { name:"depositUSDT",        type:"function", stateMutability:"nonpayable", inputs:[{name:"amount",type:"uint256"}], outputs:[] },
  { name:"withdrawUSDT",       type:"function", stateMutability:"nonpayable", inputs:[{name:"amount",type:"uint256"}], outputs:[] },
  { name:"mintStablecoin",     type:"function", stateMutability:"nonpayable", inputs:[{name:"amount",type:"uint256"}], outputs:[] },
  { name:"burnStablecoin",     type:"function", stateMutability:"nonpayable", inputs:[{name:"amount",type:"uint256"}], outputs:[] },
] as const;

export const ERC20_ABI = [
  { name:"balanceOf",       type:"function", stateMutability:"view",       inputs:[{name:"account",type:"address"}], outputs:[{type:"uint256"}] },
  { name:"allowance",       type:"function", stateMutability:"view",       inputs:[{name:"owner",type:"address"},{name:"spender",type:"address"}], outputs:[{type:"uint256"}] },
  { name:"approve",         type:"function", stateMutability:"nonpayable", inputs:[{name:"spender",type:"address"},{name:"amount",type:"uint256"}], outputs:[{type:"bool"}] },
  { name:"faucet",          type:"function", stateMutability:"nonpayable", inputs:[], outputs:[] },
  { name:"lastFaucetTime",  type:"function", stateMutability:"view",       inputs:[{name:"",type:"address"}], outputs:[{type:"uint256"}] },
  { name:"FAUCET_COOLDOWN", type:"function", stateMutability:"view",       inputs:[], outputs:[{type:"uint256"}] },
] as const;

export const ORACLE_ABI = [
  { name:"setPrice", type:"function", stateMutability:"nonpayable", inputs:[{name:"_price",type:"int256"}], outputs:[] },
] as const;

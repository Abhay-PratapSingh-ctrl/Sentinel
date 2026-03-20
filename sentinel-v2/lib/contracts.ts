export const VAULT   = "0x759d68bc0ac45d22d51a62b5867168485ba75795" as const;
export const SUSD    = "0x835a5d0bcfb1cd5f6b76fcc88df5d73ff638a5c1" as const;
export const USDT    = "0xCba9A2Dc811778773B7959FCCDA34F0132F48DB1" as const;
export const ORACLE  = "0x7de5e131658e329192fd996906fcb89b7744f709" as const;
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
  { name:"swap",            type:"function", stateMutability:"payable",    inputs:[], outputs:[] },
  { name:"lastFaucetTime",  type:"function", stateMutability:"view",       inputs:[{name:"",type:"address"}], outputs:[{type:"uint256"}] },
  { name:"FAUCET_COOLDOWN", type:"function", stateMutability:"view",       inputs:[], outputs:[{type:"uint256"}] },
] as const;

export const ORACLE_ABI = [
  { name:"setPrice", type:"function", stateMutability:"nonpayable", inputs:[{name:"_price",type:"int256"}], outputs:[] },
] as const;

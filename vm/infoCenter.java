package nachos.vm;

public class infoCenter 
{
	public VMProcess process;
	public int vpn;
	public boolean pinned;
	public boolean getRefBit()
	{
		return process.getUsedBit(vpn);
	}
	public void clearRefBit()
	{
		process.setUsedBit(vpn, false);
	}
}

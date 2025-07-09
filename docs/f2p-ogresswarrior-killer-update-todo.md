the current run code is not correct

it needs to first look if players inv needs to be banked(does it have food or not? is there enough free inv spaces? also check if the player has the required runes for the high alchemy if the box is checked on if not then do a banking run) 
if the inventory is correctly setup then the player needs to walk to the safespot
then check inside the safespot if the agro timer box is checked on or not
if yes then it needs to wait the aggro timer duration that is set by the user
if not then it can start attacking the ogress warrior

if the unagro is done 1 time it doesnt need to do the agro timer until he goes banking again
then look for loot(high alchables,gems,bread,etc)
then try attacking the ogress warrior 
loop these looting and attacking until he doesnt have food in inventory

always check if the player has a certain amount of health for example 20% if not then go banking to get more food
if there is none in bank stop the script